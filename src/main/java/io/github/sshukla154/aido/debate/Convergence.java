package io.github.sshukla154.aido.debate;

/**
 * A participant's own assessment of where they stand relative to the other side's latest
 * position. Self-reported, never inferred: the orchestrator records what each side said and
 * derives agreement only as a deterministic join over both reports.
 *
 * <p>There is deliberately no numeric score. Certainty is captured as prose in the participant's
 * own words, because a float invites arithmetic on something that was never measured.
 */
public enum Convergence {

    FULL_AGREEMENT,
    PARTIAL_AGREEMENT,
    DISAGREEMENT,

    /**
     * The participant moved. Expected to be rare in practice, because a model that revises will
     * usually report the relational question as partial agreement and signal the change through
     * {@link DebateTurn#positionChanged()} instead -- which is the field to trust.
     */
    POSITION_REVISED
}
