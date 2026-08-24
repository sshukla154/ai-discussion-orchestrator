package io.github.sshukla154.aido.provider.groq;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Outcome of one challenger request, as data rather than an exception.
 *
 * <p>Same reasoning as the Claude side: the orchestrator branches on these, so an exhaustively
 * checkable sealed hierarchy beats catch blocks the compiler cannot verify. The cases are split so
 * that two outcomes needing opposite responses never share one -- in particular, "wait and retry"
 * and "this will fail again" are different classes.
 */
public sealed interface ChallengerOutcome {

    /** A schema-valid reply. The map is the parsed structured output, ready for the turn parser. */
    record Success(Map<String, Object> structuredOutput, TokenUsage usage, long wallMillis)
            implements ChallengerOutcome {

        public Success {
            structuredOutput = Map.copyOf(structuredOutput);
        }
    }

    /**
     * Rate limited or out of quota.
     *
     * <p>Its own case because the response is to wait, not to report a fault, and because the free
     * tier makes this an ordinary event rather than an exception. The per-minute token allowance is
     * small enough that two substantive turns in quick succession will hit it.
     *
     * @param retryAfter the provider's own advice when it supplies it. Preferred over any local
     *                   back-off guess, since only the provider knows when the window resets.
     */
    record RateLimited(Optional<Duration> retryAfter, String message, long wallMillis)
            implements ChallengerOutcome {
    }

    /**
     * The request will not succeed as sent: rejected credentials, a bad model name, a schema the
     * provider refused. Distinct from {@link RateLimited} because retrying changes nothing.
     */
    record Rejected(int httpStatus, String message, long wallMillis) implements ChallengerOutcome {
    }

    /** Reached the provider, but the body was not a usable reply. Kept verbatim for diagnosis. */
    record Malformed(String reason, String rawExcerpt, long wallMillis) implements ChallengerOutcome {
    }

    /**
     * The provider was never contacted, most often because no key is configured.
     *
     * <p>Not a failure. It is the normal state of this project as designed -- the challenger runs
     * manually unless a key is present -- so the caller falls back to rendering a prompt for a
     * person rather than reporting a problem.
     */
    record Unavailable(String reason) implements ChallengerOutcome {
    }

    /** The transport failed: timeout, connection reset, DNS. Retryable, unlike {@link Rejected}. */
    record TransportFailure(String reason, long wallMillis) implements ChallengerOutcome {
    }

    /**
     * @param promptTokens     input tokens the provider counted
     * @param completionTokens output tokens the provider counted
     * @param remainingTokens  what the provider says is left in the current window, when it says.
     *                         Worth recording: on a tier where one substantive turn can consume the
     *                         whole per-minute allowance, this is the difference between pacing
     *                         correctly and discovering the limit by hitting it.
     */
    record TokenUsage(int promptTokens, int completionTokens, Optional<Integer> remainingTokens) {

        public int total() {
            return promptTokens + completionTokens;
        }
    }
}
