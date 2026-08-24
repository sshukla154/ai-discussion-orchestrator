package io.github.sshukla154.aido.debate;

/**
 * An open point of disagreement.
 *
 * <p>{@code whyItMatters} and {@code whatWouldResolveIt} exist so that a debate ending in
 * disagreement still hands the human something actionable. Without them the outcome is "they
 * disagreed", which is a dead end.
 *
 * @param blocking one participant's view that a decision cannot responsibly be made while this is
 *                 open. Expect over-reporting -- models skew toward marking things consequential
 *                 -- so this should gate an outcome only when both sides flag the same key, and
 *                 otherwise be treated as a display hint.
 */
public record Disagreement(
        String stableKey,
        String summary,
        String whyItMatters,
        String whatWouldResolveIt,
        Boolean blocking) {
}
