package io.github.sshukla154.aido.discussion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.sshukla154.aido.debate.Claim;
import io.github.sshukla154.aido.debate.Concession;
import io.github.sshukla154.aido.debate.DebateTurn;
import io.github.sshukla154.aido.debate.Disagreement;

/**
 * Tracks a discussion's claims across turns by their stable keys.
 *
 * <p>This is what lets the application say "this disagreement has been open since round one"
 * without asking a model to summarise the history. It is a fold over the typed fields of each
 * turn, so it costs nothing and produces the same answer every time.
 *
 * <p><b>Absence is not resolution.</b> A key a turn simply does not mention stays exactly as it
 * was. Only an explicit {@link Concession} retires an entry. Treating silence as agreement is the
 * single easiest way to manufacture consensus, and it would be invisible: the ledger would quietly
 * empty out and the discussion would look settled.
 *
 * <p>A model that paraphrases a key mints a new entry rather than matching an old one, which forks
 * one point into two. That is a known weakness of the whole scheme and is not compensated for
 * here. Guessing at a match by string similarity would risk the opposite and worse error --
 * merging two genuinely different points and erasing one of them. A visible fork is a cosmetic
 * defect; a silent merge rewrites what someone said.
 */
public final class ClaimLedger {

    private final Map<String, Entry> entries;

    private ClaimLedger(Map<String, Entry> entries) {
        this.entries = entries;
    }

    public static ClaimLedger empty() {
        return new ClaimLedger(Map.of());
    }

    /** Status of one tracked point. */
    public enum Status {

        /** Asserted or disputed, and not withdrawn by anyone. */
        OPEN,

        /** Explicitly conceded by the participant who previously disputed it. */
        CONCEDED
    }

    /**
     * @param summary        the point in words, carried so prompts can offer meaning rather than a
     *                       bare slug. Matching a semantic point against a list of slugs is exactly
     *                       the task a model does badly.
     * @param blockingClaims participants who flagged this as blocking a decision. A single side's
     *                       flag is a display hint; only agreement between both sides is treated as
     *                       genuinely gating, because one participant judging whether a human may
     *                       proceed reliably over-reports.
     */
    public record Entry(String stableKey, String summary, Status status, Participant raisedBy,
                        int firstSeenTurn, int lastTouchedTurn, List<Participant> blockingClaims) {

        public Entry {
            blockingClaims = List.copyOf(blockingClaims);
        }

        public boolean blockingByBothSides() {
            return blockingClaims.size() == 2;
        }
    }

    /**
     * Folds one turn into the ledger, returning a new instance.
     *
     * <p>Order within a turn matters: claims and disagreements are recorded first, then concessions
     * applied, so a turn that both restates a point and concedes it ends with the concession.
     */
    public ClaimLedger fold(DebateTurn turn, Participant speaker, int turnNumber) {
        Map<String, Entry> next = new LinkedHashMap<>(entries);

        for (Claim claim : turn.claims()) {
            touch(next, claim.stableKey(), claim.claim(), speaker, turnNumber);
        }
        for (Disagreement disagreement : turn.remainingDisagreements()) {
            Entry entry = touch(next, disagreement.stableKey(), disagreement.summary(), speaker, turnNumber);
            if (Boolean.TRUE.equals(disagreement.blocking()) && !entry.blockingClaims().contains(speaker)) {
                List<Participant> flagged = new ArrayList<>(entry.blockingClaims());
                flagged.add(speaker);
                next.put(entry.stableKey(), new Entry(entry.stableKey(), entry.summary(), entry.status(),
                        entry.raisedBy(), entry.firstSeenTurn(), turnNumber, flagged));
            }
        }
        for (Concession concession : turn.concessions()) {
            Entry existing = next.get(concession.stableKey());
            // A concession can name a point nobody recorded as a claim -- an aside the other side
            // made in prose, say. Recording it anyway keeps the concession visible rather than
            // dropping it for failing to match.
            Entry base = existing != null ? existing
                    : new Entry(concession.stableKey(), concession.nowAccepted(), Status.OPEN,
                    speaker.other(), turnNumber, turnNumber, List.of());
            next.put(base.stableKey(), new Entry(base.stableKey(), base.summary(), Status.CONCEDED,
                    base.raisedBy(), base.firstSeenTurn(), turnNumber, base.blockingClaims()));
        }
        return new ClaimLedger(next);
    }

    private static Entry touch(Map<String, Entry> into, String key, String summary,
                               Participant speaker, int turnNumber) {
        Entry existing = into.get(key);
        Entry updated = existing == null
                ? new Entry(key, summary, Status.OPEN, speaker, turnNumber, turnNumber, List.of())
                // The original wording is kept. A later turn restating a point in its own words
                // should not overwrite how it was first put, or the ledger drifts with each retelling.
                : new Entry(existing.stableKey(), existing.summary(), existing.status(),
                existing.raisedBy(), existing.firstSeenTurn(), turnNumber, existing.blockingClaims());
        into.put(key, updated);
        return updated;
    }

    public Collection<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public List<Entry> open() {
        return entries.values().stream().filter(e -> e.status() == Status.OPEN).toList();
    }

    public List<Entry> conceded() {
        return entries.values().stream().filter(e -> e.status() == Status.CONCEDED).toList();
    }

    /** Points both sides independently flagged as blocking a decision. */
    public List<Entry> blockingBothSides() {
        return entries.values().stream().filter(Entry::blockingByBothSides).toList();
    }

    /**
     * The ledger as a prompt block, one line per point.
     *
     * <p>Each line carries the key <em>and</em> the point in words, because a model asked to reuse
     * a key from a list of bare slugs paraphrases instead. Giving it the meaning is what makes
     * reuse plausible.
     */
    public String render() {
        if (entries.isEmpty()) {
            return "none yet";
        }
        StringBuilder out = new StringBuilder(entries.size() * 96);
        for (Entry e : entries.values()) {
            out.append("- ").append(e.stableKey())
                    .append(" [").append(e.status().name().toLowerCase(Locale.ROOT)).append("] ")
                    .append(e.summary()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
