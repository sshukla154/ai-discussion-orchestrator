package io.github.sshukla154.aido.provider.groq;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interpretation is a pure function over a recorded response, so every shape that matters is
 * reachable from a literal: rate limiting with and without advice, a reply cut off at the completion
 * cap, an error body echoing a credential.
 *
 * <p>A local HTTP server would exercise the transport too, and would be better where it works. It
 * does not work everywhere -- {@code HttpServer.create} needs an internal loopback socket pair for
 * its selector, which is blocked in some sandboxes while a plain {@code ServerSocket} binds fine --
 * so those tests failed on the development machine. A suite that cannot run locally is a suite that
 * gets ignored. The transport itself is covered by the connection-refused case below, and by the
 * live smoke test.
 */
class GroqChallengerProviderTest {

    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["verdict"],\
            "properties":{"verdict":{"type":"string"}}}""";

    private final GroqChallengerProvider provider = new GroqChallengerProvider();

    private static GroqResponse response(int status, String body, Map<String, String> headers) {
        return new GroqResponse(status, headers, body);
    }

    private static String completion(String content, String finishReason) {
        return """
                {"model":"openai/gpt-oss-120b","choices":[{"finish_reason":"%s",\
                "message":{"role":"assistant","content":%s}}],\
                "usage":{"prompt_tokens":2734,"completion_tokens":1980}}"""
                .formatted(finishReason, jsonString(content));
    }

    private static String jsonString(String raw) {
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Test
    @DisplayName("a strict-mode reply is parsed into the structured map")
    void parsesASuccessfulReply() {
        ChallengerOutcome outcome = provider.interpret(
                response(200, completion("{\"verdict\":\"the premise is wrong\"}", "stop"), Map.of()), 900L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.Success.class);
        ChallengerOutcome.Success success = (ChallengerOutcome.Success) outcome;
        assertThat(success.structuredOutput()).containsEntry("verdict", "the premise is wrong");
        assertThat(success.usage().total()).isEqualTo(4714);
    }

    @Test
    @DisplayName("the schema travels as a request parameter in strict mode, not inside the prompt")
    void schemaIsSentAsAParameter() {
        // This is what buys back roughly 1,300 tokens of the 8,000-per-minute budget. If the schema
        // ever migrates back into the prompt, a substantive turn stops fitting in one request.
        String body = provider.requestBody("challenge this position", SCHEMA);

        assertThat(body).contains("\"type\":\"json_schema\"");
        assertThat(body).contains("\"strict\":true");
        assertThat(body).contains("\"max_completion_tokens\"");
        assertThat(body).contains("challenge this position");
    }

    @Test
    @DisplayName("a 429 carries the provider's own retry-after rather than a local guess")
    void rateLimitUsesTheProviderRetryAfter() {
        ChallengerOutcome outcome = provider.interpret(
                response(429, "{\"error\":{\"message\":\"rate limit reached for gpt-oss-120b\"}}",
                        Map.of("Retry-After", "7.5")), 120L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.RateLimited.class);
        ChallengerOutcome.RateLimited limited = (ChallengerOutcome.RateLimited) outcome;
        // Rounded up: truncating a fractional value downward would retry one poll too early. The
        // header is capitalised here on purpose, since HTTP header names are case-insensitive.
        assertThat(limited.retryAfter()).contains(Duration.ofSeconds(8));
        assertThat(limited.message()).contains("rate limit reached");
    }

    @Test
    @DisplayName("a 429 without retry-after is still rate limiting, just without advice")
    void rateLimitWithoutHeader() {
        ChallengerOutcome outcome = provider.interpret(
                response(429, "{\"error\":{\"message\":\"slow down\"}}", Map.of()), 120L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.RateLimited.class);
        assertThat(((ChallengerOutcome.RateLimited) outcome).retryAfter()).isEmpty();
    }

    @Test
    @DisplayName("remaining token allowance is captured, since one turn can consume the window")
    void capturesRemainingTokenAllowance() {
        ChallengerOutcome outcome = provider.interpret(
                response(200, completion("{\"verdict\":\"x\"}", "stop"),
                        Map.of("x-ratelimit-remaining-tokens", "3286")), 900L);

        assertThat(((ChallengerOutcome.Success) outcome).usage().remainingTokens()).contains(3286);
    }

    @Test
    @DisplayName("a reply cut off at the completion cap is a fragment, not a challenge")
    void truncatedReplyIsMalformed() {
        // The same rule the Claude side applies to a max_tokens stop. Accepting a fragment would
        // let half an objection become the record of what the challenger argued.
        ChallengerOutcome outcome = provider.interpret(
                response(200, completion("{\"verdict\":\"the premise is wro", "length"), Map.of()), 900L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.Malformed.class);
        assertThat(((ChallengerOutcome.Malformed) outcome).reason())
                .contains("fragment")
                .contains("completion cap");
    }

    @Test
    @DisplayName("a rejected request is distinct from rate limiting, because retrying will not help")
    void rejectionIsItsOwnOutcome() {
        ChallengerOutcome outcome = provider.interpret(
                response(401, "{\"error\":{\"message\":\"Invalid API Key\"}}", Map.of()), 80L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.Rejected.class);
        ChallengerOutcome.Rejected rejected = (ChallengerOutcome.Rejected) outcome;
        assertThat(rejected.httpStatus()).isEqualTo(401);
        assertThat(rejected.message()).contains("Invalid API Key");
    }

    @Test
    @DisplayName("a credential echoed back by the provider is withheld from the outcome")
    void echoedCredentialIsWithheld() {
        // Should never happen, which is why it earns a test: an error body quoting the offending
        // request would otherwise put the key into a run record and a log line.
        ChallengerOutcome outcome = provider.interpret(
                response(400, "{\"error\":{\"message\":\"bad header: Bearer gsk_leaked_value\"}}",
                        Map.of()), 80L);

        String message = ((ChallengerOutcome.Rejected) outcome).message();
        assertThat(message).doesNotContain("gsk_leaked_value");
        assertThat(message).contains("withheld");
    }

    @Test
    @DisplayName("a non-JSON body is reported with an excerpt, never coerced")
    void nonJsonBodyIsMalformed() {
        ChallengerOutcome outcome = provider.interpret(
                response(200, "<html><body>502 Bad Gateway</body></html>", Map.of()), 80L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.Malformed.class);
        assertThat(((ChallengerOutcome.Malformed) outcome).rawExcerpt()).contains("502");
    }

    @Test
    @DisplayName("content that is valid JSON but not an object is reported, not unwrapped")
    void nonObjectContentIsMalformed() {
        assertThat(provider.interpret(
                response(200, completion("[\"not\",\"an\",\"object\"]", "stop"), Map.of()), 80L))
                .isInstanceOf(ChallengerOutcome.Malformed.class);
    }

    @Test
    @DisplayName("empty content is reported with the finish reason, not treated as a reply")
    void blankContentIsMalformed() {
        ChallengerOutcome outcome = provider.interpret(
                response(200, completion("", "content_filter"), Map.of()), 80L);

        assertThat(outcome).isInstanceOf(ChallengerOutcome.Malformed.class);
        assertThat(((ChallengerOutcome.Malformed) outcome).reason()).contains("content_filter");
    }

    @Test
    @DisplayName("a connection failure is a transport failure, and leaks no credential")
    void transportFailureLeaksNothing() {
        // Port 1 on loopback refuses immediately, so this needs no server. The second assertion is
        // the one that matters: an HTTP client exception can carry the request, and the request
        // carries the Authorization header.
        ChallengerOutcome outcome = provider.send("x", SCHEMA, "gsk_secret_value",
                URI.create("http://127.0.0.1:1/v1/chat/completions"));

        assertThat(outcome)
                .describedAs("a refused connection and an unusable network stack are both transport "
                        + "failures; either is correct here")
                .isInstanceOf(ChallengerOutcome.TransportFailure.class);
        assertThat(((ChallengerOutcome.TransportFailure) outcome).reason())
                .describedAs("an HTTP client exception can carry the request, and the request "
                        + "carries the Authorization header")
                .doesNotContain("gsk_secret_value");
    }
}
