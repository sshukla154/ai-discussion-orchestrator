package io.github.sshukla154.aido.provenance;

/**
 * The facts known only once a run finishes.
 *
 * <p>Written to a separate file whose mere existence means the run completed. A positive marker
 * rather than deleting an in-progress one: a sentinel removed on success fails open, because dying
 * between creating the directory and writing the sentinel would make an incomplete run look
 * finished. Absence-means-incomplete cannot fail that way.
 *
 * @param outcome how the run ended, which is a different question from whether it was sealed
 */
public record RunSummary(
        String outcome,
        int turnsRecorded,
        long totalInputTokens,
        long totalOutputTokens,
        String note) {
}
