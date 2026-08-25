package io.github.sshukla154.aido.discussion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import io.github.sshukla154.aido.debate.DebateTurnParser;
import io.github.sshukla154.aido.provenance.ProvenanceProperties;
import io.github.sshukla154.aido.provenance.ProvenanceStore;
import io.github.sshukla154.aido.provider.Challenger;
import io.github.sshukla154.aido.provider.claude.ClaudeCliClient;
import io.github.sshukla154.aido.provider.claude.CliResultParser;
import io.github.sshukla154.aido.provider.claude.StubCli;
import io.github.sshukla154.aido.provider.groq.ChallengerOutcome;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards a defect that shipped past 145 passing tests: the challenger answered over the API, the
 * tokens were spent, the reply was logged as {@code challenger ok} -- and then dropped, because the
 * discussion state was written with a hardcoded {@code null} in the challenger slot and no
 * provenance turn was recorded for it.
 *
 * <p>Nothing failed. The run reported success, wrote the manual paste prompt as though no answer had
 * arrived, and left a run directory with the architect turn and nothing else. That is precisely the
 * silent-loss failure the provenance record exists to make impossible, so it is worth a test that
 * asserts the record on disk rather than the log line.
 *
 * <p>The stub challenger is what made this visible at all, and is the second implementation that
 * justified extracting the interface.
 */
class RoundRunnerChallengerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path runRoot;

    @TempDir
    Path questionDir;

    /** A schema-complete turn, so the parser accepts it and only the persistence is under test. */
    private static Map<String, Object> challengerTurn() {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("stableKey", "premise-is-wrong");
        claim.put("claim", "The question assumes the four libraries move as one bloc.");
        claim.put("support", "Only one of them is referenced from main sources.");

        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("argument", "The framing is the problem, not the answer.");
        turn.put("positionSummary", "Split the bloc rather than choosing between two wrong options.");
        turn.put("claims", List.of(claim));
        turn.put("concessions", List.of());
        turn.put("remainingDisagreements", List.of());
        turn.put("openQuestions", List.of("Is the converter referenced by anything today?"));
        turn.put("convergence", "DISAGREEMENT");
        turn.put("convergenceJustification", "The position rests on a framing I reject.");
        turn.put("positionChanged", Boolean.FALSE);
        turn.put("positionChangeSummary", "");
        turn.put("acceptOtherPosition", "NO");
        turn.put("acceptanceConditions", "");
        turn.put("certainty", "Confident about the framing, less so about the cost.");
        turn.put("recommendation", "Check whether the converter is used before deciding.");
        turn.put("cannotAssess", Boolean.FALSE);
        turn.put("cannotAssessReason", "");
        return turn;
    }

    /**
     * A real {@link ClaudeCliClient} pointed at the stub process, so the architect turn travels the
     * genuine spawn-and-parse path without costing anything. The same choice the CLI tests make: a
     * mocked client cannot catch what actually breaks at that boundary.
     */
    private RoundRunner runnerWith(Challenger challenger) {
        ProvenanceStore store = new ProvenanceStore(
                new ProvenanceProperties(runRoot), Clock.systemUTC());

        String java = ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        ClaudeCliClient stubClient = new ClaudeCliClient(
                List.of(java, "-cp", System.getProperty("java.class.path"),
                        StubCli.class.getName(), "debate-turn"),
                questionDir, new CliResultParser());

        return new RoundRunner(challenger, new ObjectProvider<>() {
            @Override
            public ClaudeCliClient getObject() {
                return stubClient;
            }
        }, new DebateTurnParser(), store);
    }

    @Test
    @DisplayName("a challenger answer that arrives over the API is written to the discussion state")
    void apiAnswerIsPersisted() throws IOException {
        // The exact defect: the answer existed in memory, was logged, and never reached disk.
        Challenger stub = (prompt, schema) -> new ChallengerOutcome.Success(
                challengerTurn(), "{\"raw\":\"body\"}",
                new ChallengerOutcome.TokenUsage(3228, 2665, Optional.of(272)), 6887L);

        Path stateFile = runOneRound(stub);

        Map<?, ?> state = MAPPER.readValue(Files.readString(stateFile), Map.class);
        assertThat(state.get("challengerTurn"))
                .describedAs("a paid answer that reaches memory and not disk is silent data loss, "
                        + "and the run reports success either way")
                .isNotNull();
    }

    @Test
    @DisplayName("the challenger turn is recorded in provenance, not only in the state file")
    void apiAnswerIsRecordedAsATurn() throws IOException {
        Challenger stub = (prompt, schema) -> new ChallengerOutcome.Success(
                challengerTurn(), "{\"raw\":\"body\"}",
                new ChallengerOutcome.TokenUsage(3228, 2665, Optional.of(272)), 6887L);

        Path stateFile = runOneRound(stub);
        Path turns = stateFile.getParent().resolve("turns");

        assertThat(Files.list(turns).map(p -> p.getFileName().toString()).toList())
                .describedAs("a run directory holding only the architect turn cannot answer what "
                        + "the challenger was asked or what it replied")
                .anyMatch(name -> name.contains("challenger"));
    }

    @Test
    @DisplayName("with no answer available the state records none, rather than a fabricated one")
    void unavailableChallengerLeavesTheSlotEmpty() throws IOException {
        // The manual path. Absence must stay absence: inventing an empty turn here would put a
        // participant on record as having said nothing, which is not the same as not having spoken.
        Challenger stub = (prompt, schema) ->
                new ChallengerOutcome.Unavailable("no key configured");

        Path stateFile = runOneRound(stub);

        Map<?, ?> state = MAPPER.readValue(Files.readString(stateFile), Map.class);
        assertThat(state.get("challengerTurn")).isNull();
        assertThat(stateFile.getParent().resolve("challenger-prompt.txt"))
                .describedAs("the paste prompt is what makes the manual fallback usable")
                .exists();
    }

    @Test
    @DisplayName("a challenger that dies mid-call still leaves a run that can be finished by hand")
    void architectTurnSurvivesAChallengerCrash() {
        // The architect turn costs real money and about two minutes. Before this, the state file was
        // written after the challenger, so anything that killed the process in that window threw the
        // paid turn away and left nothing to resume from. --answer needs only the question and the
        // architect turn, so the run stays completable manually.
        Challenger exploding = (prompt, schema) -> {
            throw new IllegalStateException("provider died mid-call");
        };

        assertThatThrownBy(() -> runOneRound(exploding))
                .isInstanceOf(IllegalStateException.class);

        Path latest = newestRun();
        assertThat(latest.resolve("discussion-state.json"))
                .describedAs("the architect turn must be on disk before the challenger is called")
                .exists();

        Map<?, ?> state = readState(latest);
        assertThat(state.get("architectTurn")).isNotNull();
        assertThat(state.get("challengerTurn"))
                .describedAs("no challenger answer arrived, and none may be invented")
                .isNull();
        assertThat(state.get("question")).isNotNull();
    }

    private Path newestRun() {
        try (var dirs = Files.list(runRoot)) {
            return dirs.filter(Files::isDirectory)
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new AssertionError("no run directory was created"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<?, ?> readState(Path runDirectory) {
        try {
            return MAPPER.readValue(
                    Files.readString(runDirectory.resolve("discussion-state.json")), Map.class);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Drives one whole round, with both providers stubbed, and returns the state file. */
    private Path runOneRound(Challenger challenger) throws IOException {
        Path questionFile = questionDir.resolve("q.md");
        Files.writeString(questionFile, """
                # Question
                Should the persistence dependencies move to test scope?

                # Objective
                Decide, and state the cost.

                # Constraints
                - One writer only
                """);

        RoundRunner.Started started = runnerWith(challenger)
                .start(DiscussionQuestion.read(questionFile), "sonnet", "high");

        return started.run().runDirectory().resolve("discussion-state.json");
    }
}
