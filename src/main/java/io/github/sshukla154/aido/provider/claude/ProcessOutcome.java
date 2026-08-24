package io.github.sshukla154.aido.provider.claude;

import java.time.Instant;
import java.util.Optional;

/**
 * Raw result of running the child: what the OS gave back, before interpretation.
 *
 * <p>Kept separate from {@link CliResult} so that classification is a pure function over this
 * data and every interesting failure mode can be reproduced from a literal in a test, without
 * spawning anything. Every path in {@link ClaudeCliClient} produces one of these -- including
 * timeout and spawn failure -- so there is exactly one place where meaning is assigned.
 *
 * @param stdoutComplete   whether the stdout drain finished. This is load-bearing: an
 *                         incomplete drain also yields blank stdout, which is otherwise
 *                         indistinguishable from a pre-flight failure. Classifying a
 *                         possibly-completed turn as "the prompt never landed" would license a
 *                         duplicate retry.
 * @param promptDelivered  whether the whole prompt reached the child. A child that exits early
 *                         legitimately never reads stdin, so this is only suspicious on a path
 *                         that would otherwise report success -- there, an unconfirmed prompt
 *                         means the model may have answered a truncated question with a
 *                         perfectly well-formed envelope.
 */
public record ProcessOutcome(
        Status status,
        int exitCode,
        String stdout,
        String stderr,
        boolean stdoutComplete,
        boolean stderrComplete,
        boolean promptDelivered,
        long wallMillis,
        Optional<Long> pid,
        Optional<Instant> processStart,
        String failureMessage) {

    public enum Status {
        /** The child ran to completion and its exit code is meaningful. */
        EXITED,
        /** The child outlived its timeout and was destroyed. */
        TIMED_OUT,
        /** The child was never started. */
        SPAWN_FAILED,
        /** The calling thread was interrupted while waiting. */
        INTERRUPTED
    }

    public static ProcessOutcome exited(int exitCode, String stdout, String stderr,
                                 boolean stdoutComplete, boolean stderrComplete,
                                 boolean promptDelivered, long wallMillis,
                                 Optional<Long> pid, Optional<Instant> processStart) {
        return new ProcessOutcome(Status.EXITED, exitCode, stdout, stderr,
                stdoutComplete, stderrComplete, promptDelivered, wallMillis, pid, processStart, "");
    }

    public static ProcessOutcome timedOut(long wallMillis, Optional<Long> pid, Optional<Instant> processStart) {
        return new ProcessOutcome(Status.TIMED_OUT, -1, "", "", false, false, false,
                wallMillis, pid, processStart, "");
    }

    public static ProcessOutcome spawnFailed(String message, long wallMillis) {
        return new ProcessOutcome(Status.SPAWN_FAILED, -1, "", "", false, false, false,
                wallMillis, Optional.empty(), Optional.empty(), message);
    }

    public static ProcessOutcome interrupted(long wallMillis, Optional<Long> pid) {
        return new ProcessOutcome(Status.INTERRUPTED, -1, "", "", false, false, false,
                wallMillis, pid, Optional.empty(), "interrupted while awaiting the CLI");
    }
}
