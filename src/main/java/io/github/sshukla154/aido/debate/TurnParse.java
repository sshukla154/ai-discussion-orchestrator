package io.github.sshukla154.aido.debate;

import java.util.Map;

/**
 * Result of turning a provider's structured output into a {@link DebateTurn}.
 *
 * <p>Sealed rather than an exception, for the same reason {@code CliResult} is: a turn that ran,
 * cost money and came back with unusable structure is a different event from a turn that failed,
 * and the two want opposite recovery actions. An exception here would push that distinction into
 * a catch block where the compiler cannot check the cases are covered.
 */
public sealed interface TurnParse {

    record Parsed(DebateTurn turn) implements TurnParse {
    }

    /**
     * The provider returned something, but not a usable turn.
     *
     * @param raw kept verbatim so the payload is recoverable for diagnosis. Never repaired
     *            heuristically: a silently "fixed" convergence field would be the worst possible
     *            bug in this application, since it fabricates the one thing it exists to report
     *            honestly.
     */
    record Malformed(String reason, Map<String, Object> raw) implements TurnParse {

        public Malformed {
            raw = raw == null ? Map.of() : Map.copyOf(raw);
        }
    }

    /** The provider reported success but supplied no structured output at all. */
    record Absent(String reason) implements TurnParse {
    }
}
