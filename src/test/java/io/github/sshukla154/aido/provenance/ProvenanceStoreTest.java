package io.github.sshukla154.aido.provenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store's whole purpose is that a specific set of bytes ends up on disk, so these assertions
 * are about files and byte arrays rather than about method calls having been made. A test that
 * only verified the store was invoked would pass while it wrote nothing.
 */
class ProvenanceStoreTest {

    /**
     * A prompt built to break naive handling: a code fence, a Windows path with backslashes, a
     * literal percent, a double quote, CRLF, a tab, and a character outside the basic multilingual
     * plane. Every one of these has a plausible way of being mangled between memory and disk.
     *
     * <p>The drive path deliberately avoids the Windows user-profile prefix: the redaction
     * check forbids it in any tracked file, and rightly so, since a pattern loose enough to
     * permit a synthetic one would permit a real one. Backslashes and a drive letter are all
     * this fixture needs; the specific directory is irrelevant.
     */
    private static final String NASTY_PROMPT = "Constraints:\r\n"
            + "- path is D:\\build\\out\\file.txt and 100% of it\n"
            + "- she said \"no\"\n"
            + "\tindented\n"
            + "```java\nvar x = 1;\n```\n"
            + "emoji: \uD83D\uDE80 end";

    @TempDir
    Path runRoot;

    private ProvenanceStore store;

    @BeforeEach
    void setUp() {
        store = newStore(runRoot);
    }

    private static ProvenanceStore newStore(Path root) {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-24T10:15:33.007Z"), ZoneOffset.UTC);
        return new ProvenanceStore(new ProvenanceProperties(root), fixed);
    }

    private static RunDescriptor descriptor() {
        return new RunDescriptor("Should we retry a failed turn?", "Decide a retry policy",
                "single-writer database", "sonnet", "high", "manual paste", "0.1.0", "25");
    }

    private static TurnRequest request(int sequence, String prompt) {
        String schema = "{\"type\":\"object\"}";
        String template = "Question: {{originalQuestion}}";
        return new TurnRequest(sequence, "architect", "Architect Position", prompt,
                "architect-position", Sha256.ofUtf8(template), template,
                "v1", Sha256.ofUtf8(schema), schema,
                "claude-sonnet-5", "high", "11111111-2222-3333-4444-555555555555", "CREATE",
                300_000L, List.of("-p", "--safe-mode", "--output-format", "json"));
    }

    private static TurnResult result(String stdout) {
        return new TurnResult("Success", 0, stdout, "", true, true, true, 1234L, 4242L,
                "2026-08-24T10:15:30Z", "claude-sonnet-5", "end_turn", null,
                new TokenUsage(2L, 133L, 5528L, 0L), null);
    }

    @Test
    @DisplayName("the prompt is written byte-identical, with no escaping or normalisation")
    void promptSurvivesAsExactBytes() throws IOException {
        // The one claim the record cannot be wrong about. Asserting on bytes rather than on a
        // String, because a charset or line-ending change would compare equal as text yet be a
        // different file, and it is the file a person will read months later.
        RunHandle run = store.openRun(descriptor());

        TurnHandle turn = store.recordRequest(run, request(1, NASTY_PROMPT), null);

        byte[] onDisk = Files.readAllBytes(turn.turnDirectory().resolve("prompt.txt"));
        assertThat(onDisk).isEqualTo(NASTY_PROMPT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the recorded prompt hash matches a hash of the file actually on disk")
    void promptHashIdentifiesTheStoredFile() throws IOException {
        // Without this the hash is an unverified claim. It is also the reconciliation key against
        // the CLI's own transcript, so a mismatch would break that lookup silently.
        RunHandle run = store.openRun(descriptor());
        TurnHandle turn = store.recordRequest(run, request(1, NASTY_PROMPT), null);

        byte[] onDisk = Files.readAllBytes(turn.turnDirectory().resolve("prompt.txt"));
        String recorded = Files.readString(turn.turnDirectory().resolve("request.json"));

        assertThat(recorded).contains(Sha256.of(onDisk));
    }

    @Test
    @DisplayName("a turn with a request but no result reads as an unknown outcome")
    void requestWithoutResultIsNotAFinishedTurn() {
        // The ordering that makes an interrupted run diagnosable. If the result were written first
        // or in the same step, a process killed mid-call would leave no prompt at all.
        RunHandle run = store.openRun(descriptor());

        TurnHandle turn = store.recordRequest(run, request(1, "anything"), null);

        assertThat(turn.turnDirectory().resolve("request.json")).exists();
        assertThat(turn.turnDirectory().resolve("prompt.txt")).exists();
        assertThat(turn.turnDirectory().resolve("result.json"))
                .describedAs("absence is what marks the outcome as unknown")
                .doesNotExist();
    }

    @Test
    @DisplayName("stdout is stored verbatim rather than re-serialised")
    void stdoutIsNotRoundTripped() throws IOException {
        // Re-serialising through a JSON mapper would normalise key order and number formatting,
        // which quietly falsifies "these are the bytes received".
        String stdout = "{\"z\":1,\"a\":2.50,\"nested\":{\"b\":null}}";
        RunHandle run = store.openRun(descriptor());
        TurnHandle turn = store.recordRequest(run, request(1, "q"), null);

        store.recordResult(turn, result(stdout));

        assertThat(Files.readString(turn.turnDirectory().resolve("stdout.json"))).isEqualTo(stdout);
    }

    @Test
    @DisplayName("an empty stderr is still written, because absence would be ambiguous")
    void emptyStderrIsAPositiveStatement() {
        RunHandle run = store.openRun(descriptor());
        TurnHandle turn = store.recordRequest(run, request(1, "q"), null);

        store.recordResult(turn, result("{}"));

        assertThat(turn.turnDirectory().resolve("stderr.txt")).exists();
    }

    @Test
    @DisplayName("a long response is never truncated")
    void doesNotInheritTheParserExcerptCap() {
        // The parser caps its own excerpts at 4000 characters for diagnostics. Inheriting that
        // limit here is the most likely way to silently defeat the record, so it is asserted.
        String large = "x".repeat(50_000);
        RunHandle run = store.openRun(descriptor());
        TurnHandle turn = store.recordRequest(run, request(1, "q"), null);

        store.recordResult(turn, result(large));

        assertThat(turn.turnDirectory().resolve("stdout.json")).hasSize(50_000);
    }

    @Test
    @DisplayName("sealing writes a marker whose presence means the run finished")
    void sealingIsAPositiveMarker() {
        RunHandle run = store.openRun(descriptor());
        assertThat(run.runDirectory().resolve("run-complete.json")).doesNotExist();

        store.sealRun(run, new RunSummary("CONVERGED", 3, 100L, 200L, ""));

        assertThat(run.runDirectory().resolve("run-complete.json")).exists();
    }

    @Test
    @DisplayName("the template and schema preimages are stored, not just their hashes")
    void storesHashPreimages() throws IOException {
        // A hash without the thing it hashed is a strictly weaker record.
        RunHandle run = store.openRun(descriptor());
        store.recordRequest(run, request(1, "q"), null);

        assertThat(Files.list(run.runDirectory().resolve("templates")).toList()).hasSize(1);
        assertThat(Files.list(run.runDirectory().resolve("schemas")).toList()).hasSize(1);
    }

    @Test
    @DisplayName("run directories sort chronologically, so retention is a glob")
    void runIdsSortInStartOrder() {
        RunHandle earlier = newStore(runRoot.resolve("a")).openRun(descriptor());
        RunHandle later = new ProvenanceStore(new ProvenanceProperties(runRoot.resolve("b")),
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC))
                .openRun(descriptor());

        assertThat(earlier.runId().value()).isLessThan(later.runId().value());
    }

    @Test
    @DisplayName("recording a result never throws, because the cost is already paid")
    void recordResultDegradesRatherThanThrowing() {
        // Asymmetric on purpose: fail before spending, degrade after. Throwing here would abandon
        // the rest of a run over a turn that already succeeded and was already charged.
        RunHandle run = store.openRun(descriptor());
        TurnHandle missing = new TurnHandle(run.runId(), 9, "009-gone",
                run.runDirectory().resolve("turns").resolve("009-gone"));

        store.recordResult(missing, result("{}"));

        assertThat(missing.turnDirectory()).doesNotExist();
    }

    @Test
    @DisplayName("a role name cannot escape the run directory")
    void roleNamesAreSanitisedIntoPathComponents() {
        // Role names are data reaching the filesystem, so this is a path-injection control.
        RunHandle run = store.openRun(descriptor());
        TurnRequest traversal = new TurnRequest(1, "architect", "../../escaped", "q",
                "t", Sha256.ofUtf8("t"), "t", "v1", Sha256.ofUtf8("s"), "s",
                "m", "high", "sid", "CREATE", 1L, List.of());

        TurnHandle turn = store.recordRequest(run, traversal, null);

        assertThat(turn.turnDirectory().normalize())
                .describedAs("must stay inside the run directory")
                .startsWith(run.runDirectory().normalize());
        assertThat(turn.turnId()).doesNotContain("..");
    }

    @Test
    @DisplayName("the store refuses to write inside a git working tree")
    void refusesToWriteInsideARepository() throws IOException {
        // gitignore alone cannot make this safe: an override pointing somewhere the patterns do
        // not name defeats it silently, and the consequence on a public repository is every
        // prompt and response becoming world-readable.
        Path repo = runRoot.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path inside = repo.resolve("runs");

        assertThatThrownBy(() -> newStore(inside))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("git working tree")
                .hasMessageContaining("aido.provenance.run-root");
    }
}
