package io.github.sshukla154.aido.provenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.sshukla154.aido.common.time.UtcInstantFormat;

/**
 * Writes the on-disk record of what was sent to a provider and what came back.
 *
 * <p>Exists because context construction is a decision. Assembling a prompt drops things, and
 * months later the question "was the model wrong, or did my prompt lose the load-bearing
 * constraint?" is unanswerable unless the exact bytes are on disk. Cheap to add now and impossible
 * to add retrospectively.
 *
 * <h2>Ordering is the design</h2>
 * The request, prompt and working memory are written <b>before</b> the provider call; the result is
 * written <b>last</b>. So a run killed mid-call still has the exact prompt, and the presence of
 * {@code result.json} is what distinguishes a finished turn from one whose outcome is unknown.
 * Nothing is ever rewritten.
 *
 * <h2>Two rules that are easy to break</h2>
 * <ul>
 *   <li><b>Never truncate.</b> Prompts and responses are written whole. The result parser caps its
 *       own excerpts for diagnostics, and inheriting that cap here would defeat the record.
 *   <li><b>Never log content, at any level.</b> This class moves private reasoning from memory to
 *       disk; routing it through the logging system as well would create a second, less controlled
 *       copy of exactly the artifact that ends up pasted into a public issue.
 * </ul>
 *
 * <p>Holds no per-run mutable state, so there is nothing to lock and nothing held across a
 * provider call.
 */
@Component
public final class ProvenanceStore {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceStore.class);

    private static final int MAX_SLUG_LENGTH = 44;
    private static final int MAX_ID_ATTEMPTS = 8;

    private final Path runRoot;
    private final Clock clock;

    public ProvenanceStore(ProvenanceProperties properties, Clock clock) {
        this.runRoot = properties.runRoot().toAbsolutePath().normalize();
        this.clock = clock;
        refuseIfInsideAGitRepository(this.runRoot);
        log.info("provenance run root: {}", this.runRoot);
    }

    /**
     * Creates the run directory and records the opening facts.
     *
     * <p>Throws rather than degrading. If the record cannot be written the call must not happen,
     * because a debate that runs unrecorded is the situation this class exists to prevent.
     */
    public RunHandle openRun(RunDescriptor descriptor) {
        Instant startedAt = UtcInstantFormat.toStorablePrecision(clock.instant());
        try {
            Files.createDirectories(runRoot);
            for (int attempt = 1; ; attempt++) {
                RunId candidate = RunId.mint(startedAt);
                Path directory = runRoot.resolve(candidate.value());
                try {
                    // Fails if the name exists, so a collision becomes a retry rather than two
                    // runs interleaving into one directory.
                    Files.createDirectory(directory);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    if (attempt >= MAX_ID_ATTEMPTS) {
                        throw new IllegalStateException(
                                "could not allocate a free run id after " + attempt + " attempts", e);
                    }
                    continue;
                }
                Map<String, Object> opening = new LinkedHashMap<>();
                opening.put("runId", candidate.value());
                opening.put("startedAt", UtcInstantFormat.format(startedAt));
                opening.put("descriptor", descriptor);
                opening.put("writerPid", ProcessHandle.current().pid());
                AtomicFiles.writeString(directory.resolve("run.json"),
                        ProvenanceJson.toPrettyJson(opening));
                log.info("opened provenance run {}", candidate.value());
                return new RunHandle(candidate, directory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not open a provenance run under " + runRoot, e);
        }
    }

    /** Records the intent. Must be called before the provider is invoked. */
    public TurnHandle recordRequest(RunHandle run, TurnRequest request, String workingMemoryJson) {
        String turnId = "%03d-%s".formatted(request.sequence(), slug(request.role()));
        Path directory = run.runDirectory().resolve("turns").resolve(turnId);
        try {
            Files.createDirectories(directory);

            // The same bytes are hashed and written, so the hash genuinely identifies the file.
            // Encoding separately in each place would let a charset decision break that claim.
            byte[] promptBytes = request.promptText().getBytes(StandardCharsets.UTF_8);
            AtomicFiles.writeBytes(directory.resolve("prompt.txt"), promptBytes);

            Map<String, Object> intent = new LinkedHashMap<>();
            intent.put("sequence", request.sequence());
            intent.put("participant", request.participant());
            intent.put("role", request.role());
            intent.put("promptSha256", Sha256.of(promptBytes));
            intent.put("promptBytes", promptBytes.length);
            intent.put("templateId", request.templateId());
            intent.put("templateSha256", request.templateSha256());
            intent.put("schemaVersion", request.schemaVersion());
            intent.put("schemaSha256", request.schemaSha256());
            intent.put("model", request.model());
            intent.put("effort", request.effort());
            intent.put("sessionId", request.sessionId());
            intent.put("sessionMode", request.sessionMode());
            intent.put("timeoutMillis", request.timeoutMillis());
            intent.put("cliArgs", request.cliArgs());
            intent.put("recordedAt", UtcInstantFormat.format(
                    UtcInstantFormat.toStorablePrecision(clock.instant())));
            AtomicFiles.writeString(directory.resolve("request.json"),
                    ProvenanceJson.toPrettyJson(intent));

            if (workingMemoryJson != null) {
                // Separate from the request on purpose: the prompt shows that a constraint is
                // missing, working memory shows whether assembly lost it or rendering dropped it.
                // Different bugs, different fixes.
                AtomicFiles.writeString(directory.resolve("working-memory.json"), workingMemoryJson);
            }

            storePreimage(run, "templates", request.templateId(),
                    request.templateSha256(), request.templateText(), "txt");
            storePreimage(run, "schemas", "turn-" + request.schemaVersion(),
                    request.schemaSha256(), request.schemaText(), "json");

            log.info("recorded request run={} turn={} promptBytes={} sha={}",
                    run.runId(), turnId, promptBytes.length,
                    Sha256.filenamePrefix(Sha256.of(promptBytes)));
            return new TurnHandle(run.runId(), request.sequence(), turnId, directory);
        } catch (IOException e) {
            throw new UncheckedIOException("could not record the request for turn " + turnId, e);
        }
    }

    /**
     * Records the outcome. Logs and continues on failure rather than throwing: the call has
     * already happened and the cost is already paid, so aborting recovers nothing and would lose
     * the rest of the run. Fail before spending, degrade after.
     */
    public void recordResult(TurnHandle turn, TurnResult result) {
        try {
            // Verbatim, never re-serialised. Round-tripping through a JSON mapper would normalise
            // key order and number formatting, quietly falsifying "these are the bytes received".
            AtomicFiles.writeString(turn.turnDirectory().resolve("stdout.json"), result.rawStdout());
            // Written even when empty: an absent file is ambiguous between "nothing was written"
            // and "we did not record it", and an empty file is a positive statement.
            AtomicFiles.writeString(turn.turnDirectory().resolve("stderr.txt"), result.rawStderr());
            AtomicFiles.writeString(turn.turnDirectory().resolve("result.json"),
                    ProvenanceJson.toPrettyJson(result));
            log.info("recorded result run={} turn={} outcome={} wallMs={}",
                    turn.runId(), turn.turnId(), result.outcome(), result.wallMillis());
        } catch (IOException | RuntimeException e) {
            log.warn("could not record the result for turn {} in run {}: {}",
                    turn.turnId(), turn.runId(), e.getMessage());
        }
    }

    /** Writes the marker whose existence means the run finished. */
    public void sealRun(RunHandle run, RunSummary summary) {
        try {
            Map<String, Object> sealing = new LinkedHashMap<>();
            sealing.put("sealedAt", UtcInstantFormat.format(
                    UtcInstantFormat.toStorablePrecision(clock.instant())));
            sealing.put("summary", summary);
            AtomicFiles.writeString(run.runDirectory().resolve("run-complete.json"),
                    ProvenanceJson.toPrettyJson(sealing));
            log.info("sealed provenance run {} outcome={} turns={}",
                    run.runId(), summary.outcome(), summary.turnsRecorded());
        } catch (IOException | RuntimeException e) {
            log.warn("could not seal run {}: {}", run.runId(), e.getMessage());
        }
    }

    /**
     * Content-addressed, so the name already encodes the content and a repeat write is a no-op.
     * Storing the preimage alongside the hash matters: a hash without what it hashed is a strictly
     * weaker record, which is the same argument that motivates the whole class.
     */
    private void storePreimage(RunHandle run, String folder, String id,
                               String sha256, String content, String extension) throws IOException {
        if (content == null || content.isEmpty()) {
            return;
        }
        Path directory = run.runDirectory().resolve(folder);
        Files.createDirectories(directory);
        Path target = directory.resolve(
                "%s-%s.%s".formatted(slug(id), Sha256.filenamePrefix(sha256), extension));
        AtomicFiles.writeIfAbsent(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Role names are data that reaches the filesystem, so reducing them to a known-safe alphabet
     * is a path-injection control rather than tidiness.
     */
    private static String slug(String raw) {
        String cleaned = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("name reduces to an empty path component: " + raw);
        }
        return cleaned.length() <= MAX_SLUG_LENGTH ? cleaned : cleaned.substring(0, MAX_SLUG_LENGTH);
    }

    /**
     * Refuses to write inside a git working tree.
     *
     * <p>{@code .gitignore} cannot make this safe on its own, because an override pointing
     * somewhere the ignore patterns do not name defeats it silently. The consequence of getting it
     * wrong on a public repository is every prompt and response becoming world-readable, so this
     * errs toward a loud startup failure. It does have one real false positive: a home directory
     * that is itself a dotfiles repository.
     */
    private static void refuseIfInsideAGitRepository(Path candidate) {
        for (Path directory = candidate; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"))) {
                throw new IllegalStateException(
                        "refusing to write provenance inside a git working tree (" + directory
                                + "). Run records contain prompts and responses and must never be"
                                + " committable. Set aido.provenance.run-root elsewhere.");
            }
        }
    }
}
