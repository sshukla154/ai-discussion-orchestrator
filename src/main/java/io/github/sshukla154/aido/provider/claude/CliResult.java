package io.github.sshukla154.aido.provider.claude;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Outcome of one CLI invocation, as data rather than an exception.
 *
 * <p>Failure classes are branched on by the orchestration state machine, so they belong in an
 * exhaustively-checkable sealed hierarchy. Modelling them as exceptions would push that dispatch
 * into catch blocks where the compiler cannot tell you a case is missing.
 *
 * <p>The distinctions here are chosen so that two outcomes needing <em>opposite</em> recovery
 * actions can never share a case. A turn that never started is safe to retry; a turn that may
 * have completed server-side is not. Collapsing those into one class is how a debate ends up with
 * a duplicated turn.
 */
public sealed interface CliResult {

    /** Exit 0 with a parseable envelope and {@code is_error: false}. */
    record Success(
            String text,
            Optional<Map<String, Object>> structuredOutput,
            String sessionId,
            Optional<String> resolvedModel,
            String stopReason,
            Usage usage,
            double totalCostUsd,
            long wallMillis) implements CliResult {
    }

    /**
     * The model ran out of output budget mid-answer.
     *
     * <p>Separate from {@link Success} on purpose. A truncated argument is not a position, and
     * treating one as though it were is how half a sentence becomes the record of what a
     * participant thinks. Left inside {@code Success} this is invisible: the envelope reports no
     * error and the text simply stops.
     */
    record Truncated(
            String partialText,
            String sessionId,
            Optional<String> resolvedModel,
            Usage usage,
            long wallMillis) implements CliResult {
    }

    /**
     * Exit 1 with a parseable envelope carrying {@code is_error: true} -- an API- or model-level
     * failure. The CLI also writes a diagnostic line to stderr in this case.
     */
    record ApiError(
            String message,
            Optional<Integer> httpStatus,
            String sessionId,
            String stderr,
            long wallMillis) implements CliResult {
    }

    /**
     * The CLI has no usable login.
     *
     * <p>Its own case because retrying never helps, where retrying a transient API fault might.
     * Nothing the application can do fixes it: the operator has to re-authenticate interactively,
     * so the only useful response is to say so and stop.
     *
     * <p>Observed in the wild after a machine restart. It arrives as an ordinary error envelope --
     * {@code is_error: true} with a <b>null</b> {@code api_error_status} -- so it is indistinguishable
     * from a generic fault unless the message itself is inspected. That is why this is matched on
     * text rather than a status code, and why {@link #message()} is retained verbatim.
     */
    record AuthenticationRequired(String message, String sessionId, long wallMillis)
            implements CliResult {
    }

    /**
     * Rate limited or over quota.
     *
     * <p>Split out from {@link ApiError} because the response is to wait, not to report a fault.
     * Structurally a 429 is indistinguishable from any other error status, so without its own
     * case every caller has to remember to check the number.
     */
    record RateLimited(
            String message,
            Optional<Integer> httpStatus,
            String sessionId,
            long wallMillis) implements CliResult {
    }

    /**
     * Exit 1 with <em>no JSON at all</em>: empty stdout, a plain-text line on stderr, and
     * {@code --output-format json} silently ignored. Produced by pre-flight argument validation
     * -- malformed uuid, unknown session, a session id reused for creation, or a
     * {@code --json-schema} value that is not inline JSON.
     *
     * <p>The meaning that matters downstream is <b>the prompt never reached the model</b>, so a
     * retry is safe. Nothing may be classified here unless that is genuinely known.
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

    /**
     * The child could not be started at all.
     *
     * <p>Distinct from {@link Unparseable}, whose contract implies the CLI ran and produced
     * something unexpected. Nothing was sent, nothing was charged, and a retry is safe -- the
     * opposite conclusion from an unparseable response, which may correspond to a completed turn.
     */
    record SpawnFailed(String message, long wallMillis) implements CliResult {
    }

    /**
     * The child outlived its timeout and was destroyed, along with any descendants.
     *
     * <p>Carries the pid together with the process start instant because a pid alone does not
     * identify a process -- Windows recycles them freely. The pair is what lets a later restart
     * distinguish our surviving child from an unrelated process that inherited its number.
     *
     * <p>Says nothing about whether the turn completed server-side. It may have.
     */
    record Timeout(
            long wallMillis,
            Optional<Long> pid,
            Optional<Instant> processStart) implements CliResult {
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
