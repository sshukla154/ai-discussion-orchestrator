package io.github.sshukla154.aido.provider.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ClaudeCliClient} against a real child process.
 *
 * <p>The child is {@link StubCli} launched on this JVM, so every assertion travels the actual
 * {@link ProcessBuilder} path: argv construction and its platform-specific quoting, separate
 * stdout and stderr pipes, stdin delivery, and forcible termination. Mocking the process away
 * would leave exactly the bugs these tests exist to catch.
 */
class ClaudeCliClientProcessTest {

    @TempDir
    Path workingDir;

    private final CliResultParser parser = new CliResultParser();

    private ClaudeCliClient clientFor(String stubMode) {
        String java = ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> prefix = List.of(
                java, "-cp", System.getProperty("java.class.path"), StubCli.class.getName(), stubMode);
        return new ClaudeCliClient(prefix, workingDir, parser);
    }

    private static CliRequest request(String prompt, Duration timeout) {
        return CliRequest.stateless(prompt, timeout);
    }

    @Test
    @DisplayName("a successful child run is parsed end to end")
    void runsSuccessfully() {
        CliResult result = clientFor("success").run(request("hello", Duration.ofSeconds(60)));

        assertThat(result).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text()).isEqualTo("PONG");
    }

    @Test
    @DisplayName("output larger than any pipe buffer completes instead of deadlocking")
    void drainsBothStreamsConcurrently() {
        // The stub writes ~2 MB to stdout and ~2 MB to stderr. A client that reads one stream
        // to completion before starting the other blocks on the full buffer of the one it is
        // ignoring, and this call never returns. A generous timeout means a failure here shows
        // up as a timeout result rather than a hung suite.
        CliResult result = clientFor("flood").run(request("hello", Duration.ofSeconds(60)));

        assertThat(result)
                .describedAs("flood must not deadlock; a Timeout here means the drains are not concurrent")
                .isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text()).hasSizeGreaterThan(1_000_000);
    }

    @Test
    @DisplayName("a child that never exits is killed and reported as a timeout")
    void killsRunawayChild() {
        long start = System.nanoTime();

        CliResult result = clientFor("hang").run(request("hello", Duration.ofSeconds(2)));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(result).isInstanceOf(CliResult.Timeout.class);
        assertThat(elapsedMs)
                .describedAs("must give up near the timeout, not wait for the child")
                .isLessThan(30_000L);
    }

    @Test
    @DisplayName("a stderr-only failure is a pre-flight error, not a parse failure")
    void classifiesPreflightFromRealProcess() {
        CliResult result = clientFor("preflight-unknown-session").run(request("x", Duration.ofSeconds(60)));

        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        assertThat(((CliResult.PreflightError) result).kind())
                .isEqualTo(CliResult.PreflightError.Kind.UNKNOWN_SESSION);
    }

    @Test
    @DisplayName("a prompt with quotes, newlines, backslashes and percent survives stdin")
    void promptRoundTripsThroughStdin() {
        String nasty = "He said \"hi\"\nC:\\temp\\%PATH%\n```java\nvar x = 1;\n```\n100% done";

        CliResult result = clientFor("echo-stdin").run(request(nasty, Duration.ofSeconds(60)));

        assertThat(result).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text()).isEqualTo(nasty);
    }

    @Test
    @DisplayName("an inline JSON schema survives argv quoting")
    void jsonSchemaRoundTripsThroughArgv() {
        // The CLI rejects a file path for --json-schema, so quote-heavy JSON has to go through
        // argv. On Windows the JVM assembles a single command line with its own escaping
        // rules, which is a different code path from a shell invocation. This is the assertion
        // that says the schema arrives intact.
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[\"verdict\"],"
                + "\"properties\":{\"verdict\":{\"type\":\"string\",\"enum\":[\"YES\",\"NO\"]},"
                + "\"note\":{\"type\":\"string\",\"description\":\"a path like C:\\\\tmp and 100% of it\"}}}";

        CliResult result = clientFor("echo-args")
                .run(request("x", Duration.ofSeconds(60)).withSchema(schema));

        assertThat(result).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text())
                .describedAs("schema must arrive byte-identical in the child argv")
                .contains(schema);
    }

    @Test
    @DisplayName("every invocation disables tools and project configuration")
    void commandAlwaysHardensTheChild() {
        List<String> cmd = clientFor("success").buildCommand(request("x", Duration.ofSeconds(1)));

        assertThat(cmd).containsSequence("--output-format", "json");
        assertThat(cmd).contains("-p", "--safe-mode", "--strict-mcp-config");
        // Tools off is the injection control: one model output becomes another model input,
        // inside a CLI that can otherwise run commands and touch the filesystem.
        assertThat(cmd).containsSequence("--tools", "");
        assertThat(cmd).doesNotContain("--bare");
    }

    @Test
    @DisplayName("session mode picks the right flag, and a created id is never reused")
    void sessionFlagsMatchMode() {
        ClaudeCliClient client = clientFor("success");
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        CliRequest base = request("x", Duration.ofSeconds(1));

        assertThat(client.buildCommand(base.creating(id))).containsSequence("--session-id", id);
        assertThat(client.buildCommand(base.resuming(id))).containsSequence("--resume", id);
        assertThat(client.buildCommand(base))
                .describedAs("stateless turns carry no session flag")
                .doesNotContain("--session-id", "--resume");
    }

    @Test
    @DisplayName("a session mode without an id fails loudly rather than spawning")
    void sessionModeRequiresAnId() {
        ClaudeCliClient client = clientFor("success");
        CliRequest broken = new CliRequest("x", java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(),
                CliRequest.SessionMode.RESUME, Duration.ofSeconds(1));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> client.buildCommand(broken)))
                .hasMessageContaining("requires a sessionId");
    }
}
