package io.github.sshukla154.aido.provider.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke tests against the real installed CLI.
 *
 * <p>Tagged {@code live} and excluded from the default build. These spawn a 300+ MB binary and
 * spend real money per call, and the CLI cannot be authenticated on a CI runner without
 * putting a credential into repository secrets, which this project does not do. Run locally:
 *
 * <pre>mvn verify -Plive</pre>
 */
@Tag("live")
class ClaudeCliLiveTest {

    @TempDir
    Path workingDir;

    private final CliResultParser parser = new CliResultParser();

    private ClaudeCliClient client() {
        Optional<Path> exe = ClaudeCliLocator.locate();
        assumeTrue(exe.isPresent(), "Claude Code CLI not installed; skipping live test");
        return new ClaudeCliClient(List.of(exe.get().toString()), workingDir, parser);
    }

    @Test
    @DisplayName("a session created in one process is resumable by the next")
    void sessionSurvivesAcrossProcesses() {
        ClaudeCliClient client = client();
        String sessionId = UUID.randomUUID().toString();
        Duration timeout = Duration.ofSeconds(180);

        CliResult created = client.run(CliRequest
                .stateless("Remember this word and reply only OK: ZANZIBAR", timeout)
                .creating(sessionId));

        assertThat(created).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) created).sessionId()).isEqualTo(sessionId);

        // Separate process, and the point of the whole architecture: the conversation state
        // outlives the invocation that created it.
        CliResult resumed = client.run(CliRequest
                .stateless("What was the word? Reply with only that word.", timeout)
                .resuming(sessionId));

        assertThat(resumed).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) resumed).text()).containsIgnoringCase("ZANZIBAR");
    }

    @Test
    @DisplayName("reusing a created session id is rejected, so retries must mint a fresh one")
    void createdSessionIdCannotBeReused() {
        ClaudeCliClient client = client();
        String sessionId = UUID.randomUUID().toString();
        Duration timeout = Duration.ofSeconds(180);

        assertThat(client.run(CliRequest.stateless("Reply only OK", timeout).creating(sessionId)))
                .isInstanceOf(CliResult.Success.class);

        CliResult second = client.run(CliRequest.stateless("Reply only OK", timeout).creating(sessionId));

        assertThat(second).isInstanceOf(CliResult.PreflightError.class);
        assertThat(((CliResult.PreflightError) second).kind())
                .isEqualTo(CliResult.PreflightError.Kind.SESSION_ID_ALREADY_IN_USE);
    }

    @Test
    @DisplayName("an inline schema is enforced and returns a parsed object")
    void schemaIsEnforced() {
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[\"verdict\"],"
                + "\"properties\":{\"verdict\":{\"type\":\"string\",\"enum\":[\"YES\",\"NO\",\"UNSURE\"]}}}";

        // Asking for prose while constraining the shape: the schema has to win.
        CliResult result = client().run(CliRequest
                .stateless("Write a long essay about the ocean.", Duration.ofSeconds(180))
                .withSchema(schema));

        assertThat(result).isInstanceOf(CliResult.Success.class);
        CliResult.Success s = (CliResult.Success) result;
        assertThat(s.stopReason())
                .describedAs("a schema turns the reply into a tool call, not a plain end_turn")
                .isEqualTo("tool_use");
        assertThat(s.structuredOutput()).isPresent();
        assertThat(s.structuredOutput().get().get("verdict")).isIn("YES", "NO", "UNSURE");
        assertThat(s.structuredOutput().get().keySet()).containsExactly("verdict");
    }

    @Test
    @DisplayName("resuming an unknown session is a pre-flight failure with no JSON")
    void unknownSessionIsPreflight() {
        CliResult result = client().run(CliRequest
                .stateless("hello", Duration.ofSeconds(120))
                .resuming(UUID.randomUUID().toString()));

        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        assertThat(((CliResult.PreflightError) result).kind())
                .isEqualTo(CliResult.PreflightError.Kind.UNKNOWN_SESSION);
    }
}
