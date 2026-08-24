package io.github.sshukla154.aido.provider.claude;

import java.util.Optional;

/**
 * Raw result of running the child: exactly what the OS gave back, before interpretation.
 * Kept separate from {@link CliResult} so the parser is a pure function over these four
 * values and can be unit-tested without spawning anything.
 */
record ProcessOutcome(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        long wallMillis,
        Optional<Long> pid) {

    static ProcessOutcome timedOut(long wallMillis, Optional<Long> pid) {
        return new ProcessOutcome(-1, "", "", true, wallMillis, pid);
    }
}
