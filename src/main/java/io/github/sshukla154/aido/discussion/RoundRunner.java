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
import io.github.sshukla154.aido.provider.Challenger;
import io.github.sshukla154.aido.provider.groq.ChallengerOutcome;
import io.github.sshukla154.aido.provider.groq.GroqChallengerProvider;
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
    private static final String CHALLENGER_TEMPLATE = "challenger-challenge";

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
    private final Challenger challenger;
    private final String schemaText;

    public RoundRunner(Challenger challenger,
                       ObjectProvider<ClaudeCliClient> client, DebateTurnParser turnParser,
                       ProvenanceStore provenance) {
        this.challenger = challenger;
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

        // The prompt file is written either way. When the API path works the file is the record of
        // what was asked; when it does not, it is what a person pastes. Writing it unconditionally
        // means the manual fallback needs no extra step at the moment it is needed.
        // Written before the challenger is called, not after. The architect turn costs real money and
        // takes about two minutes, while the challenger call can fail, hang, or be interrupted. With
        // the state already on disk the run stays completable by hand through --answer, which needs
        // only the question and this turn. Writing it afterwards meant any interruption in that
        // window discarded a paid turn and left nothing to resume from.
        new DiscussionState(question.question(), question.objective(), question.constraints(),
                MAPPER.convertValue(opening, Map.class), null).writeTo(run.runDirectory());

        Optional<DebateTurn> automatic = runAutomaticChallenger(run, question, opening, ledger);
        automatic.ifPresent(turn -> log.info(
                "challenger answered over the API with {} claim(s); the round can complete "
                        + "without a person", turn.claims().size()));

        // Rewritten only when the challenger answered. The write is atomic, so a failure here leaves
        // the opening-only version intact rather than a torn file.
        automatic.ifPresent(turn -> new DiscussionState(
                question.question(), question.objective(), question.constraints(),
                MAPPER.convertValue(opening, Map.class), MAPPER.convertValue(turn, Map.class))
                .writeTo(run.runDirectory()));

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
            // Named separately from the generic error because the fix is different in kind: no
            // amount of waiting or retrying restores a login, so the message says what to do.
            case CliResult.AuthenticationRequired a -> throw new IllegalStateException(
                    "the Claude CLI has no usable login (" + a.message()
                            + "). Start an interactive session and run /login, then try again");
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
        return renderChallengerPrompt(question, opening, ledger, schemaText);
    }

    /**
     * @param schemaForPrompt the schema itself for a human who must paste it, or a short pointer
     *                        for the API path, where it travels as a request parameter instead.
     *                        That difference is worth roughly 1,300 tokens, which matters on a tier
     *                        allowing 8,000 per minute across input and output together.
     */
    private String renderChallengerPrompt(DiscussionQuestion question, DebateTurn opening,
                                          ClaimLedger ledger, String schemaForPrompt) {
        return PromptTemplate.load("challenger-challenge").render(Map.of(
                "originalQuestion", question.question(),
                "objective", question.objective(),
                "constraints", question.renderConstraints(),
                "roundQuestion", "Challenge the position above.",
                "roundNumber", "1",
                "roundCount", "1",
                "architectPosition", opening.argument() + "\n\n" + opening.positionSummary(),
                "existingClaimKeys", ledger.render(),
                "responseSchema", schemaForPrompt));
    }

    /**
     * Attempts the challenger over the API, returning empty when it is unavailable or fails.
     *
     * <p>Never throws. An absent key is the designed default rather than a fault, and a rate limit
     * on a free tier is an ordinary event -- in both cases the round degrades to the manual path
     * with the prompt already on disk, which is strictly better than aborting a run whose architect
     * turn has already been paid for.
     */
    private Optional<DebateTurn> runAutomaticChallenger(RunHandle run, DiscussionQuestion question,
                                                        DebateTurn opening, ClaimLedger ledger) {
        // The schema is a request parameter on this path, so the prompt carries a pointer instead
        // of the 4,700-byte document a human would need pasted.
        String prompt = renderChallengerPrompt(question, opening, ledger,
                "Reply as a single JSON object matching the schema supplied with this request.");

        // Recorded before the call for the same reason the architect turn is: a crash mid-request
        // must still leave the exact prompt on disk. The turn is numbered 2 whichever way it is
        // answered, so a run reads the same regardless of which challenger produced it.
        TurnHandle handle = provenance.recordRequest(run, new TurnRequest(
                2, Participant.CHALLENGER.name(), CHALLENGER_TEMPLATE, prompt,
                CHALLENGER_TEMPLATE, Sha256.ofUtf8(prompt), prompt,
                SCHEMA_VERSION, Sha256.ofUtf8(schemaText), schemaText,
                GroqChallengerProvider.MODEL, null, null, "API", 0L,
                List.of("--response-format", "json_schema")), "{}");

        ChallengerOutcome outcome = challenger.challenge(prompt, schemaText);
        provenance.recordResult(handle, TurnResult.from(outcome));

        switch (outcome) {
            case ChallengerOutcome.Success success -> {
                TurnParse parse = turnParser.parse(Optional.of(success.structuredOutput()));
                if (parse instanceof TurnParse.Parsed parsed) {
                    log.info("challenger tokens in={} out={} remaining={}",
                            success.usage().promptTokens(), success.usage().completionTokens(),
                            success.usage().remainingTokens().map(String::valueOf).orElse("unreported"));
                    return Optional.of(parsed.turn());
                }
                log.warn("challenger replied but the turn did not match the schema; "
                        + "falling back to the manual path");
                return Optional.empty();
            }
            case ChallengerOutcome.RateLimited limited -> {
                log.warn("challenger rate limited{}; falling back to the manual path",
                        limited.retryAfter().map(d -> ", retry after " + d.toSeconds() + "s").orElse(""));
                return Optional.empty();
            }
            case ChallengerOutcome.Unavailable unavailable -> {
                log.info("{}", unavailable.reason());
                return Optional.empty();
            }
            case ChallengerOutcome.Rejected rejected -> {
                log.warn("challenger rejected the request with status {}: {}",
                        rejected.httpStatus(), rejected.message());
                return Optional.empty();
            }
            case ChallengerOutcome.Malformed malformed -> {
                log.warn("challenger reply unusable: {}", malformed.reason());
                return Optional.empty();
            }
            case ChallengerOutcome.TransportFailure failure -> {
                log.warn("could not reach the challenger: {}", failure.reason());
                return Optional.empty();
            }
        }
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
