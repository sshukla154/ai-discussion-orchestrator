package io.github.sshukla154.aido.debate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One participant's turn, as typed fields rather than prose to be parsed later.
 *
 * <p>Mirrors {@code schema/debate-turn.schema.json}, which the provider validates on the way out.
 * Every turn returns both the argument and this structure in a <b>single</b> call, and the
 * discussion's working memory -- agreements, open disagreements, each side's current position --
 * is then assembled deterministically from these fields.
 *
 * <p>That is the decision this record exists to serve. The alternative was a second model call per
 * turn to summarise the first, which doubles the cost and the wall-clock of a debate and asks a
 * participant to summarise its own argument inside a system built to resist false consensus.
 *
 * <p>Field names match the schema exactly, so Jackson binds by component name with no annotations
 * and no naming strategy.
 */
public record DebateTurn(
        String argument,
        String positionSummary,
        List<Claim> claims,
        List<Concession> concessions,
        List<Disagreement> remainingDisagreements,
        List<String> openQuestions,
        Convergence convergence,
        String convergenceJustification,
        Boolean positionChanged,
        String positionChangeSummary,
        Acceptance acceptOtherPosition,
        String acceptanceConditions,
        String certainty,
        String recommendation,
        Boolean cannotAssess,
        String cannotAssessReason) {

    public DebateTurn {
        // Boxed booleans rather than primitives, deliberately. A missing field must fail here
        // rather than arrive as false, because both defaults point the wrong way: an absent
        // cannotAssess would read as "an assessment happened", and an absent positionChanged as
        // "nobody moved". Silence is not the same as a negative answer.
        Objects.requireNonNull(convergence, "convergence");
        Objects.requireNonNull(acceptOtherPosition, "acceptOtherPosition");
        Objects.requireNonNull(positionChanged, "positionChanged");
        Objects.requireNonNull(cannotAssess, "cannotAssess");

        // copyOf also rejects a null list or a null element. An omitted array and a deliberately
        // empty one are not normalised together: "we agree on nothing yet" is a real answer the
        // schema goes out of its way to permit, and collapsing it into a default would erase it.
        claims = List.copyOf(claims);
        concessions = List.copyOf(concessions);
        remainingDisagreements = List.copyOf(remainingDisagreements);
        openQuestions = List.copyOf(openQuestions);
    }

    /**
     * Whether this turn's own fields contradict each other.
     *
     * <p>Checked in code rather than argued for in the prompt, because the failure modes are
     * predictable and cheap to detect: a participant claiming full agreement while still listing
     * open disagreements, or reporting a changed position without saying what changed. Neither is
     * repaired automatically -- the inconsistency is surfaced to the human, since silently
     * picking one side of a contradiction is exactly the fabrication this design refuses.
     */
    public List<String> inconsistencies() {
        List<String> problems = new ArrayList<>(3);
        if (convergence == Convergence.FULL_AGREEMENT && !remainingDisagreements.isEmpty()) {
            problems.add("claims full agreement while listing " + remainingDisagreements.size()
                    + " remaining disagreement(s)");
        }
        if (Boolean.TRUE.equals(positionChanged) && positionChangeSummary.isBlank()) {
            problems.add("reports a changed position without saying what changed");
        }
        if (acceptOtherPosition == Acceptance.CONDITIONALLY && acceptanceConditions.isBlank()) {
            problems.add("accepts conditionally without stating the conditions");
        }
        return List.copyOf(problems);
    }

    /**
     * Whether the participant declined to assess, in which case the argument, position summary,
     * certainty and recommendation are advisory rather than a position of record. A refusing turn
     * still has to fill those fields, because the schema requires every property.
     */
    public boolean declinedToAssess() {
        return Boolean.TRUE.equals(cannotAssess);
    }
}
