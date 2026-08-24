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
 * <p>Hand-written because no JVM SDK for the CLI exists. Four properties of this class are not
 * stylistic choices and should not be "simplified":
 *
 * <ol>
 *   <li><b>stdout and stderr are drained concurrently, and so is the stdin write.</b> The two CLI
 *       error classes are distinguished by which stream carried the payload, so the streams
 *       cannot be merged. Once separated, whichever party is not being serviced blocks as soon as
 *       its pipe buffer fills -- which presents as "works on short prompts, hangs on real ones".
 *   <li><b>Nothing in the return path waits on the executor.</b> See {@link #awaitDrain}.
 *   <li><b>The prompt goes to stdin.</b> Never argv. See {@link CliRequest}.
 *   <li><b>The executable path arrives already resolved</b> from the caller (see
 *       {@link ClaudeCliLocator}) or as a stub in tests. An absolute install path is
 *       machine-identifying and must never be hardcoded into a tracked file.
 * </ol>
 *
 * <p>Every exit path produces a {@link ProcessOutcome}, so {@link CliResultParser} is the single
 * place where meaning is assigned to what the OS returned.
 */
public final class ClaudeCliClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliClient.class);

    /** How long to wait for the stream readers once the child has gone. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

    private final List<String> launchPrefix;
    private final Path workingDirectory;
    private final CliResultParser parser;

    /**
     * @param launchPrefix     command and leading arguments that start the CLI. In production a
     *                         single resolved executable path; in tests, a stub process.
     * @param workingDirectory pinned for every turn. The CLI derives transcript location and
     *                         context discovery from its cwd, so varying it makes runs
     *                         irreproducible. An empty directory also means no project
     *                         configuration is discovered.
     */
    public ClaudeCliClient(List<String> launchPrefix, Path workingDirectory, CliResultParser parser) {
        this.launchPrefix = List.copyOf(launchPrefix);
        this.workingDirectory = workingDirectory;
        this.parser = parser;
    }

    public CliResult run(CliRequest request) {
        long start = System.nanoTime();

        ProcessBuilder pb = new ProcessBuilder(buildCommand(request))
                .directory(workingDirectory.toFile())
                // Explicit despite matching the default -- see class javadoc item 1.
                .redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("could not start the Claude CLI: {}", e.getMessage());
            return parser.parse(ProcessOutcome.spawnFailed(
                    "failed to spawn CLI: " + e.getMessage(), elapsedMillis(start)));
        }

        Optional<Long> pid = safePid(process);
        Optional<Instant> processStart = process.info().startInstant();
        log.info("spawned Claude CLI pid={} sessionMode={} timeout={}s",
                pid.map(String::valueOf).orElse("?"), request.sessionMode(), request.timeout().toSeconds());

        // Not a try-with-resources. ExecutorService.close() is shutdown() followed by
        // awaitTermination(1, DAYS), and a reader parked in a native InputStream.read() cannot
        // be interrupted out of it. Closing the executor on the way out of this method would
        // therefore make the return itself block, potentially long past the timeout we just
        // reported -- the exact hang this class exists to avoid. shutdownNow() does not wait.
        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> stdout = io.submit(() -> readFully(process.getInputStream()));
            Future<String> stderr = io.submit(() -> readFully(process.getErrorStream()));
            // On its own thread as well. A prompt larger than the pipe buffer, handed to a child
            // that is alive but not reading, would otherwise block the calling thread with no
            // timeout at all. Debate prompts carrying a transcript pass that size easily.
            Future<Boolean> stdin = io.submit(() -> writePromptToStdin(process, request.prompt()));

            boolean exited = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                log.warn("Claude CLI exceeded {}s, destroying pid={} and descendants",
                        request.timeout().toSeconds(), pid.map(String::valueOf).orElse("?"));
                killTree(process);
                return parser.parse(ProcessOutcome.timedOut(elapsedMillis(start), pid, processStart));
            }

            Drained out = awaitDrain(stdout, "stdout");
            Drained err = awaitDrain(stderr, "stderr");
            boolean promptDelivered = awaitPromptDelivery(stdin);

            CliResult result = parser.parse(ProcessOutcome.exited(
                    process.exitValue(), out.text(), err.text(), out.complete(), err.complete(),
                    promptDelivered, elapsedMillis(start), pid, processStart));
            logOutcome(result, processStart);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return parser.parse(ProcessOutcome.interrupted(elapsedMillis(start), pid));
        } finally {
            io.shutdownNow();
        }
    }

    List<String> buildCommand(CliRequest request) {
        List<String> cmd = new ArrayList<>(launchPrefix);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");

        // Injection defence and cost control in one move. The debate loop feeds the output of one
        // model to another model running inside an agentic CLI, so tools are off. Measured side
        // effect: cold-start cache-creation tokens drop by roughly an order of magnitude, because
        // no project configuration, skills, plugins or MCP servers are loaded.
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
        // Inline only -- the CLI rejects a file path for --json-schema. Pre-escaped for Windows
        // argv; see WindowsArgv.
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

    /**
     * Writes the prompt and closes stdin so the child sees end of input.
     *
     * <p>A broken pipe from a child that has already exited is expected and uninteresting: the
     * exit code and stderr explain what happened. A write failing while the child is still
     * <em>alive</em> is a different matter -- stdin gets closed regardless by the
     * try-with-resources, so the child receives a truncated prompt followed by an unexpected end
     * of input, and may well answer a mangled question with a perfectly well-formed envelope.
     * That deserves a warning rather than a debug line nobody sees.
     */
    private boolean writePromptToStdin(Process process, String prompt) {
        try (OutputStream in = process.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
            in.flush();
            return true;
        } catch (IOException e) {
            if (process.isAlive()) {
                log.warn("failed to write the prompt to a live CLI process ({}); "
                        + "it may have received a truncated prompt", e.getMessage());
            } else {
                log.debug("CLI exited before reading its prompt: {}", e.getMessage());
            }
            return false;
        }
    }

    /**
     * @return whether the whole prompt is known to have reached the child. A {@code false} is not
     *         automatically a failure -- a child that exits early on a pre-flight error
     *         legitimately never reads stdin -- but it does mean a success envelope cannot be
     *         trusted, because the model may have answered a truncated question.
     */
    private boolean awaitPromptDelivery(Future<Boolean> stdin) {
        try {
            return Boolean.TRUE.equals(stdin.get(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            stdin.cancel(true);
            log.warn("the prompt was still being written when the CLI exited; "
                    + "it may have received only part of it");
            return false;
        } catch (ExecutionException e) {
            log.warn("writing the prompt failed: {}",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String readFully(InputStream stream) throws IOException {
        try (InputStream s = stream) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Captured stream content, and whether the capture actually finished.
     *
     * <p>The flag matters because an unfinished read yields an empty string, which is
     * indistinguishable from a process that wrote nothing. Reporting the difference is what stops
     * a possibly-completed turn being classified as one that never started.
     */
    private record Drained(String text, boolean complete) {
    }

    private Drained awaitDrain(Future<String> future, String streamName) {
        try {
            return new Drained(future.get(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS), true);
        } catch (TimeoutException e) {
            // Cancelling sends an interrupt, which a thread parked in a native read ignores. The
            // task is abandoned rather than stopped; it ends when the pipe closes.
            future.cancel(true);
            log.error("{} could not be read within {}s of the CLI exiting; the outcome of this "
                            + "turn is unknown and it must not be retried blindly",
                    streamName, DRAIN_GRACE.toSeconds());
            return new Drained("", false);
        } catch (ExecutionException e) {
            log.warn("reading {} failed: {}", streamName,
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return new Drained("", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Drained("", false);
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
     * Deliberately never logs a prompt or a response body. Both carry private reasoning, and a log
     * file is exactly the artefact that ends up pasted into a public issue tracker.
     */
    private void logOutcome(CliResult result, Optional<Instant> processStart) {
        switch (result) {
            case CliResult.Success s -> log.info(
                    "CLI ok model={} stop={} in={} out={} cacheCreate={} cacheRead={} cost={} wallMs={} started={}",
                    s.resolvedModel().orElse("unresolved"), s.stopReason(), s.usage().inputTokens(),
                    s.usage().outputTokens(), s.usage().cacheCreationInputTokens(),
                    s.usage().cacheReadInputTokens(), s.totalCostUsd(), s.wallMillis(),
                    processStart.map(String::valueOf).orElse("?"));
            case CliResult.Truncated t -> log.warn(
                    "CLI hit the output ceiling model={} out={} wallMs={}; the reply is a fragment, "
                            + "not a position", t.resolvedModel().orElse("unresolved"),
                    t.usage().outputTokens(), t.wallMillis());
            case CliResult.RateLimited r -> log.warn("CLI rate limited status={} wallMs={}",
                    r.httpStatus().map(String::valueOf).orElse("-"), r.wallMillis());
            case CliResult.ApiError e -> log.warn("CLI api error status={} wallMs={}",
                    e.httpStatus().map(String::valueOf).orElse("-"), e.wallMillis());
            case CliResult.PreflightError e -> log.warn("CLI preflight error kind={} wallMs={}",
                    e.kind(), e.wallMillis());
            case CliResult.SpawnFailed f -> log.error("CLI never started: {}", f.message());
            case CliResult.Timeout t -> log.warn("CLI timeout wallMs={}", t.wallMillis());
            case CliResult.Unparseable u -> log.error("CLI unparseable exit={} reason={}",
                    u.exitCode(), u.reason());
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
