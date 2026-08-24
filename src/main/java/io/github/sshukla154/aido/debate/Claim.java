package io.github.sshukla154.aido.debate;

/**
 * One assertion a participant is making, tagged with a key that survives across rounds.
 *
 * @param stableKey lowercase-hyphenated slug. Reused when a later turn addresses the same point,
 *                  which is what lets a single disagreement be tracked as it evolves without an
 *                  extra model call. Models paraphrase these, so a key vanishing from a turn
 *                  never means the point was resolved -- only an explicit concession retires it.
 */
public record Claim(String stableKey, String claim, String support) {
}
