package io.github.sshukla154.aido.provider.claude;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
        CliResult result = clientFor("success").run(request("hello", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text()).isEqualTo("PONG");
    }

    @Test
    @DisplayName("output larger than any pipe buffer completes instead of deadlocking")
    // An independent backstop. Production code converts a deadlock into a clean Timeout, but a
    // regression that blocks before ever reaching waitFor would otherwise hang the fork with no
    // bound at all, and surefire has none configured.
    @Timeout(120)
    void drainsBothStreamsConcurrently() {
        // The stub writes ~2 MB to stdout and ~2 MB to stderr. A client that reads one stream
        // to completion before starting the other blocks on the full buffer of the one it is
        // ignoring, and this call never returns. A generous timeout means a failure here shows
        // up as a timeout result rather than a hung suite.
        CliResult result = clientFor("flood").run(request("hello", Duration.ofSeconds(60))).result();

        assertThat(result)
                .describedAs("flood must not deadlock; a Timeout here means the drains are not concurrent")
                .isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text()).hasSizeGreaterThan(1_000_000);
    }

    @Test
    @DisplayName("a child that never exits is killed and reported as a timeout")
    void killsRunawayChild() {
        long start = System.nanoTime();

        CliResult result = clientFor("hang").run(request("hello", Duration.ofSeconds(2))).result();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(result).isInstanceOf(CliResult.Timeout.class);
        assertThat(elapsedMs)
                .describedAs("must give up near the timeout, not wait for the child")
                .isLessThan(30_000L);
    }

    @Test
    @DisplayName("a stderr-only failure is a pre-flight error, not a parse failure")
    void classifiesPreflightFromRealProcess() {
        CliResult result = clientFor("preflight-unknown-session").run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        assertThat(((CliResult.PreflightError) result).kind())
                .isEqualTo(CliResult.PreflightError.Kind.UNKNOWN_SESSION);
    }

    @Test
    @DisplayName("a prompt with quotes, newlines, backslashes and percent survives stdin")
    void promptRoundTripsThroughStdin() {
        String nasty = "He said \"hi\"\nC:\\temp\\%PATH%\n```java\nvar x = 1;\n```\n100% done";

        CliResult result = clientFor("echo-stdin").run(request(nasty, Duration.ofSeconds(60))).result();

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
                .run(request("x", Duration.ofSeconds(60)).withSchema(schema)).result();

        assertThat(result).isInstanceOf(CliResult.Success.class);
        assertThat(((CliResult.Success) result).text())
                .describedAs("schema must arrive byte-identical in the child argv")
                .contains(schema);
    }

    @Test
    @DisplayName("an API error envelope survives the real process boundary")
    void classifiesApiErrorFromRealProcess() {
        CliResult result = clientFor("apierror").run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.ApiError.class);
        assertThat(((CliResult.ApiError) result).httpStatus()).contains(404);
    }

    @Test
    @DisplayName("a schema-bearing reply arrives parsed, with stop_reason tool_use")
    void parsesStructuredOutputFromRealProcess() {
        CliResult result = clientFor("schema").run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.Success.class);
        CliResult.Success s = (CliResult.Success) result;
        assertThat(s.stopReason()).isEqualTo("tool_use");
        assertThat(s.structuredOutput()).isPresent();
        assertThat(s.structuredOutput().get()).containsEntry("verdict", "YES");
    }

    @Test
    @DisplayName("a rejected schema is a pre-flight failure, not a parse failure")
    void classifiesRejectedSchemaFromRealProcess() {
        CliResult result = clientFor("preflight-bad-schema").run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.PreflightError.class);
        assertThat(((CliResult.PreflightError) result).kind())
                .isEqualTo(CliResult.PreflightError.Kind.INVALID_JSON_SCHEMA);
    }

    @Test
    @DisplayName("garbage on stdout is reported, never coerced into a success")
    void malformedJsonFromRealProcessIsUnparseable() {
        CliResult result = clientFor("malformed-json").run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.Unparseable.class);
    }

    @Test
    @DisplayName("silence on both streams is unparseable, not a silent pre-flight error")
    void emptyOutputFromRealProcessIsUnparseable() {
        CliResult result = clientFor("empty").run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.Unparseable.class);
    }

    @Test
    @DisplayName("the invocation carries the raw bytes that classification discards")
    void invocationExposesTheRawOutcome() {
        // The reason run() returns CliInvocation at all. Success carries no stdout, so a
        // provenance record built from the classification alone could not state what came
        // back -- which is the one question the record exists to answer.
        CliInvocation invocation = clientFor("success").run(request("hello", Duration.ofSeconds(60)));

        assertThat(invocation.result()).isInstanceOf(CliResult.Success.class);
        assertThat(invocation.outcome().stdout())
                .describedAs("the exact envelope must survive, not a summary of it")
                .contains("\"result\":\"PONG\"")
                .contains("modelUsage");
        assertThat(invocation.outcome().status()).isEqualTo(ProcessOutcome.Status.EXITED);
        assertThat(invocation.outcome().promptDelivered()).isTrue();
        assertThat(invocation.outcome().pid()).isPresent();
    }

    @Test
    @DisplayName("recorded args exclude the executable path, which is machine-identifying")
    void recordedArgsOmitTheExecutable() {
        // The repository is public and a CI check fails the build on home-directory paths in
        // tracked files. Provenance writes these args to disk, so the path must never be in them.
        CliInvocation invocation = clientFor("success").run(request("hello", Duration.ofSeconds(60)));

        assertThat(invocation.args())
                .describedAs("argv tail only")
                .contains("-p", "--safe-mode", "--output-format", "json")
                .noneMatch(a -> a.contains("java") || a.contains(StubCli.class.getName()));
    }

    @Test
    @DisplayName("a command that cannot be started is a spawn failure, not an odd response")
    void reportsSpawnFailure() {
        // Nothing was sent and nothing was charged, so this is safe to retry -- the opposite
        // conclusion from an unparseable response, which may correspond to a completed turn.
        ClaudeCliClient broken = new ClaudeCliClient(
                List.of(workingDir.resolve("no-such-executable").toString()), workingDir, parser);

        CliResult result = broken.run(request("x", Duration.ofSeconds(60))).result();

        assertThat(result).isInstanceOf(CliResult.SpawnFailed.class);
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
