package io.github.sshukla154.aido.discussion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.sshukla154.aido.debate.DebateTurn;
import io.github.sshukla154.aido.debate.DebateTurnParser;
import io.github.sshukla154.aido.debate.PromptTemplate;
import io.github.sshukla154.aido.debate.TurnParse;
import io.github.sshukla154.aido.provenance.ProvenanceStore;
import io.github.sshukla154.aido.provenance.RunDescriptor;
import io.github.sshukla154.aido.provenance.RunHandle;
import io.github.sshukla154.aido.provenance.RunId;
import io.github.sshukla154.aido.provenance.RunSummary;
import io.github.sshukla154.aido.provenance.Sha256;
import io.github.sshukla154.aido.provenance.TurnHandle;
import io.github.sshukla154.aido.provenance.TurnRequest;
import io.github.sshukla154.aido.provenance.TurnResult;
import io.github.sshukla154.aido.provider.claude.ClaudeCliClient;
import io.github.sshukla154.aido.provider.claude.CliInvocation;
import io.github.sshukla154.aido.provider.claude.CliRequest;
import io.github.sshukla154.aido.provider.claude.CliResult;

/**
 * Runs one round: architect states a position, a challenger attacks it, the architect responds.
 *
 * <p>Split into two phases because the challenger is a person pasting into a separate chat. The
 * first phase ends by handing over a prompt and stopping; the second resumes from
 * {@link DiscussionState} once a reply exists. Nothing waits on a human, and nothing holds a
 * resource across the gap.
 *
 * <p>Not {@code @Transactional}, and nothing here may become so: a turn can legitimately run for
 * minutes, and an architecture test fails the build if anything that can reach the CLI client
 * acquires a transaction.
 */
@Component
public final class RoundRunner {

    private static final Logger log = LoggerFactory.getLogger(RoundRunner.class);

    /** Bumped when the schema changes shape, so a recorded turn says which contract it answered. */
    private static final String SCHEMA_VERSION = "1";

    private static final String SCHEMA_RESOURCE = "schema/debate-turn.schema.json";
    private static final String CHALLENGER_PROMPT_FILE = "challenger-prompt.txt";

    /**
     * Generous, because the failure it guards is a hung subprocess rather than a slow model. A
     * substantive architecture turn on a high effort setting runs for minutes.
     */
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(5);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Resolved per call rather than injected directly. {@code ClaudeCliClient} is final, so a lazy
     * proxy is impossible, and constructing it requires an installed CLI -- which the application
     * must be able to start without.
     */
    private final ObjectProvider<ClaudeCliClient> client;
    private final DebateTurnParser turnParser;
    private final ProvenanceStore provenance;
    private final String schemaText;

    public RoundRunner(ObjectProvider<ClaudeCliClient> client, DebateTurnParser turnParser,
                       ProvenanceStore provenance) {
        this.client = client;
        this.turnParser = turnParser;
        this.provenance = provenance;
        this.schemaText = readSchema();
    }

    /**
     * Runs the architect's opening turn and writes the prompt the human should paste to a
     * challenger.
     *
     * @return where the round is recorded, and the file holding the challenger prompt
     */
    public Started start(DiscussionQuestion question, String model, String effort) {
        RunHandle run = provenance.openRun(new RunDescriptor(
                question.question(), question.objective(), question.renderConstraints(),
                model, effort, "manual paste into a separate chat",
                appVersion(), System.getProperty("java.version")));

        PromptTemplate template = PromptTemplate.load("architect-position");
        String prompt = template.render(Map.of(
                "originalQuestion", question.question(),
                "objective", question.objective(),
                "constraints", question.renderConstraints(),
                "roundQuestion", "State your position on the question above."));

        DebateTurn opening = runArchitectTurn(run, 1, "architect-position", template, prompt, model, effort);

        ClaimLedger ledger = ClaimLedger.empty().fold(opening, Participant.ARCHITECT, 1);
        String challengerPrompt = renderChallengerPrompt(question, opening, ledger);

        Path promptFile = run.runDirectory().resolve(CHALLENGER_PROMPT_FILE);
        write(promptFile, challengerPrompt);

        new DiscussionState(question.question(), question.objective(), question.constraints(),
                MAPPER.convertValue(opening, Map.class), null).writeTo(run.runDirectory());

        log.info("round started run={} openingClaims={} openPoints={}",
                run.runId(), opening.claims().size(), ledger.open().size());
        return new Started(run, promptFile);
    }

    /**
     * Ingests a challenger reply, runs the architect's response, and writes the artifact.
     *
     * @param replyFile the challenger's structured reply, as pasted back from their chat
     */
    public Path answer(Path runDirectory, Path replyFile, Path artifactFile, String model, String effort) {
        DiscussionState state = DiscussionState.readFrom(runDirectory);
        DiscussionQuestion question = state.asQuestion();

        DebateTurn opening = requireTurn(
                turnParser.parse(Optional.of(state.architectTurn())), "the recorded opening turn");
        DebateTurn challenge = requireTurn(parseReply(replyFile), "the challenger reply in " + replyFile);

        ClaimLedger ledger = ClaimLedger.empty()
                .fold(opening, Participant.ARCHITECT, 1)
                .fold(challenge, Participant.CHALLENGER, 2);

        // The directory name is the run id, and RunId validates the shape, so a hand-typed or
        // renamed directory is rejected here rather than producing a half-recorded second phase.
        RunHandle run = new RunHandle(new RunId(runDirectory.getFileName().toString()), runDirectory);
        PromptTemplate template = PromptTemplate.load("architect-response");
        String prompt = template.render(Map.of(
                "originalQuestion", question.question(),
                "objective", question.objective(),
                "constraints", question.renderConstraints(),
                "roundQuestion", "Respond to the criticism. Revise where it lands, and say what remains open.",
                "roundNumber", "1",
                "roundCount", "1",
                "challengerCritique", challenge.argument() + "\n\n" + challenge.positionSummary(),
                "existingClaimKeys", ledger.render()));

        DebateTurn response = runArchitectTurn(run, 3, "architect-response", template, prompt, model, effort);
        ledger = ledger.fold(response, Participant.ARCHITECT, 3);

        state.withChallengerTurn(MAPPER.convertValue(challenge, Map.class)).writeTo(runDirectory);

        String artifact = DiscussionArtifact.render(question, ledger, opening, challenge, response,
                runDirectory.getFileName().toString());
        write(artifactFile, artifact);

        provenance.sealRun(run, new RunSummary("COMPLETED", 3, 0L, 0L,
                "one round, challenger answered by manual paste"));

        log.info("round complete run={} settled={} open={} bothSidesBlocking={}",
                runDirectory.getFileName(), ledger.conceded().size(), ledger.open().size(),
                ledger.blockingBothSides().size());
        return artifactFile;
    }

    private DebateTurn runArchitectTurn(RunHandle run, int sequence, String templateId,
                                        PromptTemplate template, String prompt,
                                        String model, String effort) {
        CliRequest request = CliRequest.stateless(prompt, TURN_TIMEOUT)
                .withSchema(schemaText)
                .withModel(model)
                .withEffort(effort);

        // Recorded before the call, so a crash mid-turn still leaves the exact prompt on disk.
        TurnHandle handle = provenance.recordRequest(run, new TurnRequest(
                sequence, Participant.ARCHITECT.name(), templateId, prompt,
                templateId, Sha256.ofUtf8(template.source()), template.source(),
                SCHEMA_VERSION, Sha256.ofUtf8(schemaText), schemaText,
                model, effort, null, "STATELESS", TURN_TIMEOUT.toMillis(),
                recordedArgs(model, effort)), "{}");

        CliInvocation invocation = client.getObject().run(request);
        provenance.recordResult(handle, TurnResult.from(invocation));

        return switch (invocation.result()) {
            case CliResult.Success s -> requireTurn(turnParser.parse(s.structuredOutput()),
                    "the architect turn");
            // Every other case is a refusal to guess. A truncated reply in particular is a
            // fragment, not a position, and recording it as one is the failure the sealed result
            // hierarchy exists to prevent.
            case CliResult.Truncated t -> throw new IllegalStateException(
                    "the architect ran out of output budget after " + t.usage().outputTokens()
                            + " tokens, so the reply is a fragment rather than a position");
            case CliResult.RateLimited r -> throw new IllegalStateException(
                    "rate limited; wait and resume from the run directory");
            case CliResult.ApiError e -> throw new IllegalStateException(
                    "the CLI reported an error: " + e.message());
            case CliResult.PreflightError e -> throw new IllegalStateException(
                    "the CLI rejected the invocation (" + e.kind() + "): " + e.stderr());
            case CliResult.SpawnFailed f -> throw new IllegalStateException(
                    "could not start the CLI: " + f.message());
            case CliResult.Timeout t -> throw new IllegalStateException(
                    "the architect turn exceeded " + TURN_TIMEOUT.toMinutes() + " minutes");
            case CliResult.Unparseable u -> throw new IllegalStateException(
                    "the CLI produced an outcome that cannot be interpreted: " + u.reason());
        };
    }

    private String renderChallengerPrompt(DiscussionQuestion question, DebateTurn opening,
                                          ClaimLedger ledger) {
        return PromptTemplate.load("challenger-challenge").render(Map.of(
                "originalQuestion", question.question(),
                "objective", question.objective(),
                "constraints", question.renderConstraints(),
                "roundQuestion", "Challenge the position above.",
                "roundNumber", "1",
                "roundCount", "1",
                "architectPosition", opening.argument() + "\n\n" + opening.positionSummary(),
                "existingClaimKeys", ledger.render(),
                "responseSchema", schemaText));
    }

    private TurnParse parseReply(Path replyFile) {
        String text = read(replyFile).strip();
        if (text.isEmpty()) {
            return new TurnParse.Absent("the reply file " + replyFile + " is empty");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = MAPPER.readValue(text, LinkedHashMap.class);
            return turnParser.parse(Optional.of(raw));
        } catch (RuntimeException e) {
            return new TurnParse.Malformed(
                    "the reply file is not the JSON object the schema asks for: " + e.getMessage(),
                    Map.of());
        }
    }

    private static DebateTurn requireTurn(TurnParse parse, String what) {
        return switch (parse) {
            case TurnParse.Parsed p -> p.turn();
            case TurnParse.Malformed m -> throw new IllegalStateException(
                    what + " did not match the schema: " + m.reason());
            case TurnParse.Absent a -> throw new IllegalStateException(what + " is missing: " + a.reason());
        };
    }

    private static String readSchema() {
        try (var in = RoundRunner.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("schema not on the classpath: " + SCHEMA_RESOURCE);
            }
            // Stripped because the value goes straight into argv, where a trailing newline added by
            // an editor or a line-ending conversion would become part of a live argument.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + SCHEMA_RESOURCE, e);
        }
    }

    private static String appVersion() {
        String version = RoundRunner.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /**
     * The flags this turn was invoked with.
     *
     * <p>The schema is referenced by hash rather than repeated: its full text is already stored as
     * a preimage in the run, and a multi-kilobyte value inside an argv list makes the record
     * unreadable for no gain. The hardening flags are fixed by the client and recorded there.
     */
    private List<String> recordedArgs(String model, String effort) {
        return List.of("--model", model, "--effort", effort,
                "--json-schema", "sha256:" + Sha256.filenamePrefix(Sha256.ofUtf8(schemaText)));
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** Where a started round lives, and the prompt awaiting a human. */
    public record Started(RunHandle run, Path challengerPromptFile) {
    }
}
