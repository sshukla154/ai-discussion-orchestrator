package io.github.sshukla154.aido.provider.claude;

import java.util.Map;
import java.util.Optional;

/**
 * Outcome of one CLI invocation, as data rather than an exception.
 *
 * <p>Failure classes are branched on by the orchestration state machine, so they belong in
 * an exhaustively-checkable sealed hierarchy. Modelling them as exceptions would push that
 * dispatch into catch blocks where the compiler cannot tell you a case is missing.
 */
public sealed interface CliResult {

    /** Exit 0 with a parseable envelope and {@code is_error: false}. */
    record Success(
            String text,
            Optional<Map<String, Object>> structuredOutput,
            String sessionId,
            String resolvedModel,
            String stopReason,
            Usage usage,
            double totalCostUsd,
            long wallMillis) implements CliResult {
    }

    /**
     * Exit 1 with a parseable envelope carrying {@code is_error: true} — an API- or
     * model-level failure. The CLI also writes a diagnostic line to stderr in this case.
     */
    record ApiError(
            String message,
            Optional<Integer> httpStatus,
            String sessionId,
            String stderr,
            long wallMillis) implements CliResult {
    }

    /**
     * Exit 1 with <em>no JSON at all</em>: empty stdout, a plain-text line on stderr, and
     * {@code --output-format json} silently ignored. Produced by pre-flight argument
     * validation — malformed uuid, unknown session, a session id reused for creation, or
     * a {@code --json-schema} value that is not inline JSON. A parser that assumes stdout
     * is always JSON fails on every one of these.
     */
    record PreflightError(
            Kind kind,
            String stderr,
            long wallMillis) implements CliResult {

        public enum Kind {
            UNKNOWN_SESSION,
            INVALID_SESSION_ID,
            SESSION_ID_ALREADY_IN_USE,
            INVALID_JSON_SCHEMA,
            NOT_LOGGED_IN,
            OTHER
        }
    }

    /** The child outlived its timeout and was destroyed, along with any descendants. */
    record Timeout(long wallMillis, Optional<Long> pid) implements CliResult {
    }

    /** Exit code and streams did not match any known shape. Never silently coerced. */
    record Unparseable(int exitCode, String stdoutExcerpt, String stderrExcerpt, String reason,
                       long wallMillis) implements CliResult {
    }

    record Usage(
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens) {
    }
}
