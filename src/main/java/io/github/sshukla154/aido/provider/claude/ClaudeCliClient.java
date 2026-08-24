package io.github.sshukla154.aido.provider.claude;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the Claude Code CLI as a child process and reports a typed outcome.
 *
 * <p>Hand-written because no JVM SDK for the CLI exists. Three properties of this class are
 * not stylistic choices and should not be "simplified":
 *
 * <ol>
 *   <li><b>stdout and stderr are drained concurrently.</b> The two CLI error classes are
 *       distinguished by which stream carried the payload, so the streams cannot be merged.
 *       Once separated, a child whose pipe buffer fills while nobody reads it blocks
 *       forever, which presents as "works on short prompts, hangs on real ones".
 *   <li><b>The prompt goes to stdin.</b> Never argv. See {@link CliRequest}.
 *   <li><b>The executable is resolved from PATH at runtime.</b> An absolute install path is
 *       machine-identifying and must never reach a tracked file.
 * </ol>
 */
public class ClaudeCliClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliClient.class);

    /** Grace period for the drain tasks to finish after the child has exited. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

    private final List<String> launchPrefix;
    private final Path workingDirectory;
    private final CliResultParser parser;

    /**
     * @param launchPrefix     command and leading arguments that start the CLI. In production a
     *                         single resolved executable path; in tests, a stub process.
     * @param workingDirectory pinned for every turn. The CLI derives transcript location and
     *                         context discovery from its cwd, so varying it makes runs
     *                         irreproducible. An empty directory also means no project config
     *                         is discovered.
     */
    public ClaudeCliClient(List<String> launchPrefix, Path workingDirectory, CliResultParser parser) {
        this.launchPrefix = List.copyOf(launchPrefix);
        this.workingDirectory = workingDirectory;
        this.parser = parser;
    }

    public CliResult run(CliRequest request) {
        List<String> command = buildCommand(request);
        long start = System.nanoTime();

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                // Mandatory: merging the streams would destroy the only signal that
                // separates a pre-flight failure from an API error.
                .redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new CliResult.Unparseable(-1, "", "", "failed to spawn CLI: " + e.getMessage(),
                    elapsedMillis(start));
        }

        Optional<Long> pid = safePid(process);
        // Captured together: a pid alone is not identifying, because Windows recycles pids
        // freely. The pair is what lets a later restart tell "still our child" from "some
        // unrelated process that inherited the number".
        Optional<Instant> procStart = process.info().startInstant();
        log.info("spawned Claude CLI pid={} sessionMode={} timeout={}s",
                pid.map(String::valueOf).orElse("?"), request.sessionMode(), request.timeout().toSeconds());

        try (ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = drains.submit(() -> readFully(process.getInputStream()));
            Future<String> stderr = drains.submit(() -> readFully(process.getErrorStream()));

            writePromptToStdin(process, request.prompt());

            boolean exited = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                log.warn("Claude CLI exceeded {}s, destroying pid={} and descendants",
                        request.timeout().toSeconds(), pid.map(String::valueOf).orElse("?"));
                killTree(process);
                return new CliResult.Timeout(elapsedMillis(start), pid);
            }

            ProcessOutcome outcome = new ProcessOutcome(
                    process.exitValue(),
                    awaitDrain(stdout),
                    awaitDrain(stderr),
                    false,
                    elapsedMillis(start),
                    pid);

            CliResult result = parser.parse(outcome);
            logOutcome(result, procStart);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return new CliResult.Unparseable(-1, "", "", "interrupted while awaiting CLI",
                    elapsedMillis(start));
        }
    }

    List<String> buildCommand(CliRequest request) {
        List<String> cmd = new ArrayList<>(launchPrefix);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");

        // Injection defence and cost control in one move. The debate loop feeds the output of
        // one model to another model running inside an agentic CLI, so tools are off.
        // Measured side effect: cold-start cache-creation tokens drop by roughly an order of
        // magnitude, because no project config, skills, plugins or MCP servers are loaded.
        cmd.add("--safe-mode");
        cmd.add("--strict-mcp-config");
        cmd.add("--tools");
        cmd.add("");

        switch (request.sessionMode()) {
            case CREATE -> {
                cmd.add("--session-id");
                cmd.add(requireSessionId(request));
            }
            case RESUME -> {
                cmd.add("--resume");
                cmd.add(requireSessionId(request));
            }
            case STATELESS -> {
                // no session flag; the CLI allocates one and returns it
            }
        }

        request.model().ifPresent(m -> {
            cmd.add("--model");
            cmd.add(m);
        });
        request.effort().ifPresent(e -> {
            cmd.add("--effort");
            cmd.add(e);
        });
        // Inline only. The CLI rejects a path with "--json-schema is not valid JSON", so the
        // schema has to travel through argv, where Windows would otherwise eat every quote and
        // split the value on its first space. See WindowsArgv for the measured behaviour.
        request.jsonSchema().ifPresent(s -> {
            cmd.add("--json-schema");
            cmd.add(WindowsArgv.encode(s));
        });
        return cmd;
    }

    private String requireSessionId(CliRequest request) {
        return request.sessionId().orElseThrow(() ->
                new IllegalArgumentException("sessionMode=" + request.sessionMode() + " requires a sessionId"));
    }

    private void writePromptToStdin(Process process, String prompt) {
        try (OutputStream in = process.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException e) {
            // A child that died before reading stdin yields a broken pipe. That is not the
            // interesting failure, the exit code and stderr are, so record and continue.
            log.debug("could not write prompt to CLI stdin: {}", e.getMessage());
        }
    }

    private String readFully(InputStream stream) throws IOException {
        try (InputStream s = stream) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String awaitDrain(Future<String> future) {
        try {
            return future.get(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "";
        } catch (ExecutionException e) {
            log.debug("stream drain failed: {}",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Descendants first, so no grandchild outlives the process we were asked to stop. */
    private void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Optional<Long> safePid(Process process) {
        try {
            return Optional.of(process.pid());
        } catch (UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    /**
     * Deliberately never logs a prompt or a response body. Both carry private reasoning, and
     * a log file is exactly the artefact that ends up pasted into a public issue tracker.
     */
    private void logOutcome(CliResult result, Optional<Instant> procStart) {
        switch (result) {
            case CliResult.Success s -> log.info(
                    "CLI ok model={} stop={} in={} out={} cacheCreate={} cacheRead={} cost={} wallMs={} started={}",
                    s.resolvedModel(), s.stopReason(), s.usage().inputTokens(), s.usage().outputTokens(),
                    s.usage().cacheCreationInputTokens(), s.usage().cacheReadInputTokens(),
                    s.totalCostUsd(), s.wallMillis(), procStart.map(String::valueOf).orElse("?"));
            case CliResult.ApiError e -> log.warn("CLI api error status={} wallMs={}",
                    e.httpStatus().map(String::valueOf).orElse("-"), e.wallMillis());
            case CliResult.PreflightError e -> log.warn("CLI preflight error kind={} wallMs={}",
                    e.kind(), e.wallMillis());
            case CliResult.Timeout t -> log.warn("CLI timeout wallMs={}", t.wallMillis());
            case CliResult.Unparseable u -> log.error("CLI unparseable exit={} reason={}",
                    u.exitCode(), u.reason());
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
