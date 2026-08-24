package io.github.sshukla154.aido.provider.claude;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures here are transcribed from real CLI output. Each one encodes a behaviour that is
 * surprising enough to regress if the parser is ever "tidied up".
 */
class CliResultParserTest {

    private final CliResultParser parser = new CliResultParser();

    private static ProcessOutcome outcome(int exit, String stdout, String stderr) {
        return new ProcessOutcome(exit, stdout, stderr, false, 1234L, Optional.of(4242L));
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

        CliResult result = parser.parse(outcome(0, stdout, ""));

        assertThat(result).isInstanceOf(CliResult.Success.class);
        CliResult.Success s = (CliResult.Success) result;
        assertThat(s.text()).isEqualTo("PONG");
        assertThat(s.sessionId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(s.stopReason()).isEqualTo("end_turn");
        assertThat(s.resolvedModel()).isEqualTo("claude-sonnet-5");
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
                outcome(1, stdout, "[claude-code:unrecognized_model] {\"model\":\"nope\"}"));

        assertThat(result).isInstanceOf(CliResult.ApiError.class);
        CliResult.ApiError e = (CliResult.ApiError) result;
        assertThat(e.httpStatus()).contains(404);
        assertThat(e.message()).contains("issue with the selected model");
        assertThat(e.stderr()).contains("unrecognized_model");
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
        CliResult result = parser.parse(outcome(1, "", "Error: something entirely new happened"));

        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        CliResult.PreflightError e = (CliResult.PreflightError) result;
        assertThat(e.kind()).isEqualTo(CliResult.PreflightError.Kind.OTHER);
        assertThat(e.stderr()).isEqualTo("Error: something entirely new happened");
    }

    @Test
    @DisplayName("the auxiliary model in modelUsage is not mistaken for the one that answered")
    void resolvesPrimaryModelNotTheAuxiliaryOne() {
        // The real CLI reports a lightweight model alongside the primary, for side work such
        // as session-title generation. Reading "the only key" would report the wrong model.
        String stdout = """
                {"is_error":false,"result":"ok","session_id":"s","stop_reason":"end_turn",\
                "usage":{"input_tokens":2,"output_tokens":133},\
                "modelUsage":{\
                "claude-haiku-4-5-20251001":{"inputTokens":902,"outputTokens":12},\
                "claude-sonnet-5":{"inputTokens":2,"outputTokens":133}}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(outcome(0, stdout, ""));

        assertThat(s.resolvedModel()).isEqualTo("claude-sonnet-5");
    }

    @Test
    @DisplayName("structured_output is exposed as a parsed map and flips stop_reason to tool_use")
    void parsesStructuredOutput() {
        String stdout = """
                {"is_error":false,"result":"{\\"verdict\\":\\"YES\\"}","session_id":"s",\
                "stop_reason":"tool_use","usage":{"output_tokens":5},\
                "modelUsage":{"claude-sonnet-5":{"outputTokens":5}},\
                "structured_output":{"verdict":"YES","reason":"because"}}""";

        CliResult.Success s = (CliResult.Success) parser.parse(outcome(0, stdout, ""));

        assertThat(s.stopReason()).isEqualTo("tool_use");
        assertThat(s.structuredOutput()).isPresent();
        assertThat(s.structuredOutput().get()).containsEntry("verdict", "YES");
    }

    @Test
    @DisplayName("malformed stdout is reported, never coerced into a success")
    void malformedJsonIsUnparseable() {
        CliResult result = parser.parse(outcome(0, "{\"type\":\"result\", not json", ""));

        assertThat(result).isInstanceOf(CliResult.Unparseable.class);
        assertThat(((CliResult.Unparseable) result).reason()).contains("not valid JSON");
    }

    @Test
    @DisplayName("both streams empty is unparseable, not a silent pre-flight error")
    void bothStreamsEmptyIsUnparseable() {
        assertThat(parser.parse(outcome(1, "", ""))).isInstanceOf(CliResult.Unparseable.class);
    }

    @Test
    @DisplayName("a timeout short-circuits before any stream interpretation")
    void timeoutWins() {
        ProcessOutcome timedOut = ProcessOutcome.timedOut(300_000L, Optional.of(99L));

        CliResult result = parser.parse(timedOut);

        assertThat(result).isInstanceOf(CliResult.Timeout.class);
        assertThat(((CliResult.Timeout) result).pid()).contains(99L);
    }

    private CliResult.PreflightError.Kind kindOf(String stderr) {
        CliResult result = parser.parse(outcome(1, "", stderr));
        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        return ((CliResult.PreflightError) result).kind();
    }
}
