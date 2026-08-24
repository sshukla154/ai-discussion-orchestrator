package io.github.sshukla154.aido.provider.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Smoke-runs one real turn against the installed CLI and prints the typed result.
 *
 * <p>This exists so the riskiest integration in the project is demonstrable from the first
 * commit rather than after a layer of abstraction has been built on top of assumptions. It
 * asks a trivial question under a schema, then reports the parsed fields and token usage.
 *
 * <p>Run with: {@code mvn -q compile spring-boot:run -Dspring-boot.run.arguments=--probe}
 */
@Component
public final class ClaudeProbeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProbeRunner.class);

    /**
     * Minified deliberately. The schema can only be passed inline as an argument, so every
     * byte competes for the command-line length budget.
     */
    private static final String PROBE_SCHEMA = """
            {"type":"object","additionalProperties":false,\
            "required":["verdict","reason"],\
            "properties":{"verdict":{"type":"string","enum":["YES","NO","UNSURE"]},\
            "reason":{"type":"string"}}}""";

    private final CliResultParser parser;

    public ClaudeProbeRunner(CliResultParser parser) {
        this.parser = parser;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption("probe")) {
            log.info("Nothing to do. Pass --probe to smoke-test the Claude CLI integration.");
            return;
        }

        Optional<Path> executable = ClaudeCliLocator.locate();
        if (executable.isEmpty()) {
            log.error("Claude Code CLI not found on PATH. Set -D{} to its full path.",
                    ClaudeCliLocator.OVERRIDE_PROPERTY);
            return;
        }

        Path cwd = pinnedWorkingDirectory();
        ClaudeCliClient client = new ClaudeCliClient(
                List.of(executable.get().toString()), cwd, parser);

        CliRequest request = CliRequest
                .stateless("Is the sky blue? Answer in the schema.", Duration.ofSeconds(120))
                .withSchema(PROBE_SCHEMA);

        report(client.run(request));
    }

    private void report(CliResult result) {
        switch (result) {
            case CliResult.Success s -> {
                log.info("PROBE OK");
                log.info("  session       : {}", s.sessionId());
                log.info("  model         : {}", s.resolvedModel().orElse("unresolved"));
                log.info("  stop_reason   : {}", s.stopReason());
                // Response content, so DEBUG only -- the rule logOutcome already follows.
                log.debug("  structured    : {}", s.structuredOutput().orElse(null));
                log.info("  tokens        : in={} out={} cacheCreate={} cacheRead={}",
                        s.usage().inputTokens(), s.usage().outputTokens(),
                        s.usage().cacheCreationInputTokens(), s.usage().cacheReadInputTokens());
                log.info("  cost / wall   : {} USD / {} ms", s.totalCostUsd(), s.wallMillis());
            }
            case CliResult.Truncated t ->
                    log.error("PROBE hit the output ceiling after {} tokens; the reply is a fragment",
                            t.usage().outputTokens());
            // Error text arrives in the same envelope field as an ordinary reply, so it is
            // treated as response content and kept at DEBUG.
            case CliResult.RateLimited r -> {
                log.error("PROBE rate limited status={}",
                        r.httpStatus().map(String::valueOf).orElse("-"));
                log.debug("  message       : {}", r.message());
            }
            case CliResult.ApiError e -> {
                log.error("PROBE api error status={}",
                        e.httpStatus().map(String::valueOf).orElse("-"));
                log.debug("  message       : {}", e.message());
            }
            case CliResult.SpawnFailed f ->
                    log.error("PROBE could not start the CLI: {}", f.message());
            case CliResult.PreflightError e ->
                    log.error("PROBE preflight error kind={} stderr={}", e.kind(), e.stderr());
            case CliResult.Timeout t ->
                    log.error("PROBE timed out after {} ms", t.wallMillis());
            case CliResult.Unparseable u ->
                    log.error("PROBE unparseable exit={} reason={}", u.exitCode(), u.reason());
        }
    }

    /**
     * One directory, reused for every turn, outside the repository.
     *
     * <p>Pinning it keeps runs reproducible: the CLI derives its transcript location from the
     * working directory. Keeping it empty means no project configuration is discovered, which
     * is both the cheap path and the safe one.
     */
    private Path pinnedWorkingDirectory() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".ai-discussion-orchestrator", "cli-cwd");
        Files.createDirectories(dir);
        return dir;
    }
}
