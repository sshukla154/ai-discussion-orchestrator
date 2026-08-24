package io.github.sshukla154.aido.provider.claude;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures here are transcribed from real CLI output. Each encodes a behaviour surprising enough
 * to regress if the parser is ever tidied up.
 */
class CliResultParserTest {

    private final CliResultParser parser = new CliResultParser();

    private static ProcessOutcome exited(int exit, String stdout, String stderr) {
        return ProcessOutcome.exited(exit, stdout, stderr, true, true, 1234L,
                Optional.of(4242L), Optional.of(Instant.parse("2026-08-24T11:00:00Z")));
    }

    @Test
    @DisplayName("success envelope yields text, session, usage and cost")
    void parsesSuccess() {
        String stdout = """
                {"type":"result","subtype":"success","is_error":false,"result":"PONG",\
                "session_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","stop_reason":"end_turn",\
                "total_cost_usd":0.0244,\
                "usage":{"input_tokens":2,"output_tokens":133,\
                "cache_creation_input_tokens":5528,"cache_read_input_tokens":0},\
                "modelUsage":{"claude-sonnet-5":{"inputTokens":2,"outputTokens":133}}}""";

        CliResult result = parser.parse(exited(0, stdout, ""));

        assertThat(result).isInstanceOf(CliResult.Success.class);
        CliResult.Success s = (CliResult.Success) result;
        assertThat(s.text()).isEqualTo("PONG");
        assertThat(s.sessionId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(s.stopReason()).isEqualTo("end_turn");
        assertThat(s.resolvedModel()).contains("claude-sonnet-5");
        assertThat(s.totalCostUsd()).isEqualTo(0.0244);
        assertThat(s.usage().cacheCreationInputTokens()).isEqualTo(5528L);
        assertThat(s.structuredOutput()).isEmpty();
    }

    @Test
    @DisplayName("is_error=true is an API error even though subtype still reads success")
    void subtypeIsNotASuccessSignal() {
        String stdout = """
                {"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error",\
                "api_error_status":404,"result":"There is an issue with the selected model.",\
                "session_id":"11111111-2222-3333-4444-555555555555","total_cost_usd":0,"modelUsage":{}}""";

        CliResult result = parser.parse(
                exited(1, stdout, "[claude-code:unrecognized_model] {\"model\":\"nope\"}"));

        assertThat(result).isInstanceOf(CliResult.ApiError.class);
        CliResult.ApiError e = (CliResult.ApiError) result;
        assertThat(e.httpStatus()).contains(404);
        assertThat(e.message()).contains("issue with the selected model");
        assertThat(e.stderr()).contains("unrecognized_model");
    }

    @Test
    @DisplayName("a 429 is rate limiting, not a generic API error")
    void rateLimitIsItsOwnClass() {
        // Structurally identical to any other error status. Without its own case, every caller
        // has to remember to check the number, and the correct response here is to wait rather
        // than to report a fault.
        String stdout = """
                {"is_error":true,"api_error_status":429,"result":"Rate limit exceeded.",\
                "session_id":"s","modelUsage":{}}""";

        CliResult result = parser.parse(exited(1, stdout, "rate limited"));

        assertThat(result).isInstanceOf(CliResult.RateLimited.class);
        assertThat(((CliResult.RateLimited) result).httpStatus()).contains(429);
    }

    @Test
    @DisplayName("a max_tokens stop is a fragment, never a position")
    void truncatedRepliesAreNotSuccesses() {
        // The failure this prevents: a reply that ran out of output budget reports no error and
        // simply stops, so inside Success it would be recorded as what a participant thinks.
        String stdout = """
                {"is_error":false,"result":"I think the first consideration is",\
                "session_id":"s","stop_reason":"max_tokens",\
                "usage":{"output_tokens":4096},\
                "modelUsage":{"claude-sonnet-5":{"outputTokens":4096}}}""";

        CliResult result = parser.parse(exited(0, stdout, ""));

        assertThat(result).isInstanceOf(CliResult.Truncated.class);
        CliResult.Truncated t = (CliResult.Truncated) result;
        assertThat(t.partialText()).startsWith("I think the first consideration");
        assertThat(t.resolvedModel()).contains("claude-sonnet-5");
    }

    @Test
    @DisplayName("pre-flight failures carry no JSON at all and must not be parsed as one")
    void classifiesPreflightErrors() {
        assertThat(kindOf("No conversation found with session ID: 9999"))
                .isEqualTo(CliResult.PreflightError.Kind.UNKNOWN_SESSION);
        assertThat(kindOf("Error: Invalid session ID. Must be a valid UUID."))
                .isEqualTo(CliResult.PreflightError.Kind.INVALID_SESSION_ID);
        assertThat(kindOf("Error: Session ID 1111 is already in use."))
                .isEqualTo(CliResult.PreflightError.Kind.SESSION_ID_ALREADY_IN_USE);
        assertThat(kindOf("Error: --json-schema is not valid JSON: JSON Parse error"))
                .isEqualTo(CliResult.PreflightError.Kind.INVALID_JSON_SCHEMA);
        assertThat(kindOf("Not logged in - Please run /login"))
                .isEqualTo(CliResult.PreflightError.Kind.NOT_LOGGED_IN);
    }

    @Test
    @DisplayName("an unrecognised pre-flight message classifies as OTHER and keeps the raw text")
    void unknownPreflightIsNotAParseFailure() {
        CliResult result = parser.parse(exited(1, "", "Error: something entirely new happened"));

        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        CliResult.PreflightError e = (CliResult.PreflightError) result;
        assertThat(e.kind()).isEqualTo(CliResult.PreflightError.Kind.OTHER);
        assertThat(e.stderr()).isEqualTo("Error: something entirely new happened");
    }

    @Test
    @DisplayName("an incomplete stdout capture is never classified as a safe-to-retry pre-flight error")
    void incompleteCaptureIsUnknownNotPreflight() {
        // A drain that did not finish also leaves stdout blank. Reading that as "the prompt
        // never landed" would license a retry of a turn that may already have completed and
        // been charged, which is how a debate acquires a duplicated turn.
        ProcessOutcome partial = ProcessOutcome.exited(0, "", "some stderr noise",
                false, true, 100L, Optional.of(1L), Optional.empty());

        CliResult result = parser.parse(partial);

        assertThat(result).isInstanceOf(CliResult.Unparseable.class);
        assertThat(((CliResult.Unparseable) result).reason())
                .contains("could not be captured completely")
                .contains("may have completed");
    }

    @Test
    @DisplayName("the auxiliary model in modelUsage is not mistaken for the one that answered")
    void resolvesPrimaryModelNotTheAuxiliaryOne() {
        String stdout = """
                {"is_error":false,"result":"ok","session_id":"s","stop_reason":"end_turn",\
                "usage":{"input_tokens":2,"output_tokens":133},\
                "modelUsage":{\
                "claude-haiku-4-5-20251001":{"inputTokens":902,"outputTokens":12},\
                "claude-sonnet-5":{"inputTokens":2,"outputTokens":133}}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(exited(0, stdout, ""));

        assertThat(s.resolvedModel()).contains("claude-sonnet-5");
    }

    @Test
    @DisplayName("an unresolvable model is reported as unknown rather than guessed")
    void doesNotGuessTheModelWhenNothingMatches() {
        // "Largest producer wins" is biased exactly the wrong way here: a schema-constrained
        // reply is a handful of tokens, so an ordinary auxiliary call easily emits more. Since
        // this value is provenance, a wrong answer dressed as a right one is worse than none.
        String stdout = """
                {"is_error":false,"result":"ok","session_id":"s","stop_reason":"tool_use",\
                "usage":{"output_tokens":7},\
                "modelUsage":{\
                "claude-haiku-4-5-20251001":{"outputTokens":900},\
                "claude-sonnet-5":{"outputTokens":133}}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(exited(0, stdout, ""));

        assertThat(s.resolvedModel()).isEmpty();
    }

    @Test
    @DisplayName("two models reporting the same output count is ambiguous, not a determination")
    void doesNotGuessOnATie() {
        String stdout = """
                {"is_error":false,"result":"ok","session_id":"s","stop_reason":"end_turn",\
                "usage":{"output_tokens":0},\
                "modelUsage":{"model-a":{"outputTokens":0},"model-b":{"outputTokens":0}}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(exited(0, stdout, ""));

        assertThat(s.resolvedModel()).isEmpty();
    }

    @Test
    @DisplayName("a single-entry modelUsage needs no matching at all")
    void resolvesTheOnlyModel() {
        String stdout = """
                {"is_error":false,"result":"ok","session_id":"s","stop_reason":"end_turn",\
                "usage":{"output_tokens":99},\
                "modelUsage":{"claude-opus-5":{"outputTokens":4}}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(exited(0, stdout, ""));

        assertThat(s.resolvedModel()).contains("claude-opus-5");
    }

    @Test
    @DisplayName("structured_output is exposed as a parsed map and flips stop_reason to tool_use")
    void parsesStructuredOutput() {
        String stdout = """
                {"is_error":false,"result":"{\\"verdict\\":\\"YES\\"}","session_id":"s",\
                "stop_reason":"tool_use","usage":{"output_tokens":5},\
                "modelUsage":{"claude-sonnet-5":{"outputTokens":5}},\
                "structured_output":{"verdict":"YES","reason":"because"}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(exited(0, stdout, ""));

        assertThat(s.stopReason()).isEqualTo("tool_use");
        assertThat(s.structuredOutput()).isPresent();
        assertThat(s.structuredOutput().get()).containsEntry("verdict", "YES");
    }

    @Test
    @DisplayName("malformed stdout is reported, never coerced into a success")
    void malformedJsonIsUnparseable() {
        CliResult result = parser.parse(exited(0, "{\"type\":\"result\", not json", ""));

        assertThat(result).isInstanceOf(CliResult.Unparseable.class);
        assertThat(((CliResult.Unparseable) result).reason()).contains("not valid JSON");
    }

    @Test
    @DisplayName("both streams empty is unparseable, not a silent pre-flight error")
    void bothStreamsEmptyIsUnparseable() {
        assertThat(parser.parse(exited(1, "", ""))).isInstanceOf(CliResult.Unparseable.class);
    }

    @Test
    @DisplayName("a failure to spawn is its own class, since nothing was sent or charged")
    void spawnFailureIsNotAnUnparseableResponse() {
        // Unparseable implies the CLI ran and produced something odd. A spawn failure means it
        // never started -- the two demand opposite recovery actions.
        CliResult result = parser.parse(ProcessOutcome.spawnFailed("CreateProcess error=2", 12L));

        assertThat(result).isInstanceOf(CliResult.SpawnFailed.class);
        assertThat(((CliResult.SpawnFailed) result).message()).contains("error=2");
    }

    @Test
    @DisplayName("a timeout carries the pid and start instant together, since a pid alone is not an identity")
    void timeoutCarriesThePidReuseGuard() {
        Instant started = Instant.parse("2026-08-24T11:00:00Z");

        CliResult result = parser.parse(
                ProcessOutcome.timedOut(300_000L, Optional.of(99L), Optional.of(started)));

        assertThat(result).isInstanceOf(CliResult.Timeout.class);
        CliResult.Timeout t = (CliResult.Timeout) result;
        assertThat(t.pid()).contains(99L);
        assertThat(t.processStart())
                .describedAs("Windows recycles pids, so the start instant is what makes the pid identifying")
                .contains(started);
    }

    private CliResult.PreflightError.Kind kindOf(String stderr) {
        CliResult result = parser.parse(exited(1, "", stderr));
        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        return ((CliResult.PreflightError) result).kind();
    }
}
