package io.github.sshukla154.aido.discussion;

import java.util.List;

import io.github.sshukla154.aido.debate.Convergence;
import io.github.sshukla154.aido.debate.DebateTurn;
import io.github.sshukla154.aido.debate.Disagreement;

/**
 * Renders a finished round as markdown.
 *
 * <p>This is the product. Everything else exists to produce a document a person pastes into a
 * design doc or a pull request, so it is written for a reader who was not present and will not
 * open the run directory.
 *
 * <p>Two rules shape it. It never claims a decision was made -- the human decides, and the
 * document ends by saying what they have to decide. And it never reports agreement that both
 * sides did not independently state: where the two self-reports disagree, that mismatch is
 * printed as a finding in its own right, because "they disagree about whether they agree" is more
 * useful than either side's summary.
 */
final class DiscussionArtifact {

    private DiscussionArtifact() {
    }

    static String render(DiscussionQuestion question, ClaimLedger ledger,
                         DebateTurn architect, DebateTurn challenger, DebateTurn response,
                         String runId) {
        StringBuilder out = new StringBuilder(8_192);

        out.append("# ").append(question.question()).append("\n\n");
        out.append("**Objective** ").append(question.objective()).append("\n\n");
        if (!question.constraints().isEmpty()) {
            out.append("**Constraints**\n\n").append(question.renderConstraints()).append("\n\n");
        }

        out.append("## Where they landed\n\n").append(verdict(challenger, response)).append("\n\n");

        appendPositions(out, response, challenger);
        appendAgreed(out, ledger);
        appendOpen(out, ledger, challenger, response);
        appendInconsistencies(out, architect, challenger, response);
        appendDecision(out, ledger, response);
        appendArguments(out, architect, challenger, response);

        out.append("\n---\n\n")
                .append("Run `").append(runId).append("`. Prompts, raw responses and token usage ")
                .append("are recorded there; this document is derived from them.\n");
        return out.toString();
    }

    /**
     * The headline, computed from two independent self-reports rather than asked of either.
     *
     * <p>Full agreement requires both sides to say so. Anything else is reported as what it is,
     * and a straight contradiction between the two reports is called out rather than averaged
     * into a middle value that neither participant stated.
     */
    private static String verdict(DebateTurn challenger, DebateTurn response) {
        Convergence byChallenger = challenger.convergence();
        Convergence byArchitect = response.convergence();

        if (byChallenger == Convergence.FULL_AGREEMENT && byArchitect == Convergence.FULL_AGREEMENT) {
            return "Both participants independently report full agreement.";
        }
        boolean oneClaimsAgreement = byChallenger == Convergence.FULL_AGREEMENT
                || byArchitect == Convergence.FULL_AGREEMENT;
        boolean oneClaimsDisagreement = byChallenger == Convergence.DISAGREEMENT
                || byArchitect == Convergence.DISAGREEMENT;
        if (oneClaimsAgreement && oneClaimsDisagreement) {
            return "**The two participants disagree about whether they agree.** The Challenger reports "
                    + label(byChallenger) + " and the Architect reports " + label(byArchitect)
                    + ". That gap is itself the finding: read both positions below before deciding.";
        }
        return "Challenger reports " + label(byChallenger) + "; Architect reports "
                + label(byArchitect) + ". No claim of full agreement from either side.";
    }

    private static void appendPositions(StringBuilder out, DebateTurn response, DebateTurn challenger) {
        out.append("## Positions\n\n");
        out.append("**Architect**\n\n").append(response.positionSummary()).append("\n\n");
        out.append("**Challenger**\n\n").append(challenger.positionSummary()).append("\n\n");

        if (Boolean.TRUE.equals(response.positionChanged())) {
            out.append("The Architect's position moved during the round: ")
                    .append(response.positionChangeSummary()).append("\n\n");
        }
    }

    private static void appendAgreed(StringBuilder out, ClaimLedger ledger) {
        List<ClaimLedger.Entry> conceded = ledger.conceded();
        out.append("## Settled\n\n");
        if (conceded.isEmpty()) {
            // Stated rather than omitted. An absent section reads as an oversight; this reads as a
            // result, and agreeing on nothing is a legitimate outcome of one round.
            out.append("Nothing was conceded by either side in this round.\n\n");
            return;
        }
        for (ClaimLedger.Entry e : conceded) {
            out.append("- ").append(e.summary())
                    .append("  \n  _conceded, raised in turn ").append(e.firstSeenTurn()).append("_\n");
        }
        out.append('\n');
    }

    private static void appendOpen(StringBuilder out, ClaimLedger ledger,
                                   DebateTurn challenger, DebateTurn response) {
        out.append("## Still open\n\n");
        List<ClaimLedger.Entry> open = ledger.open();
        if (open.isEmpty()) {
            out.append("No open points were recorded.\n\n");
            return;
        }
        for (ClaimLedger.Entry e : open) {
            out.append("### ").append(e.summary()).append("\n\n");
            detail(out, challenger, e.stableKey());
            detail(out, response, e.stableKey());
            if (e.blockingByBothSides()) {
                out.append("Both sides consider this blocking.\n\n");
            }
        }
    }

    /** The actionable half of a disagreement: what it costs, and what would settle it. */
    private static void detail(StringBuilder out, DebateTurn turn, String stableKey) {
        for (Disagreement d : turn.remainingDisagreements()) {
            if (d.stableKey().equals(stableKey)) {
                out.append("- Why it matters: ").append(d.whyItMatters()).append('\n');
                out.append("- What would settle it: ").append(d.whatWouldResolveIt()).append("\n\n");
                return;
            }
        }
    }

    /**
     * Contradictions inside a single turn, surfaced rather than repaired.
     *
     * <p>Silently picking one side of a model contradicting itself would be exactly the
     * fabrication this application refuses. The reader gets to see it.
     */
    private static void appendInconsistencies(StringBuilder out, DebateTurn... turns) {
        List<String> all = List.of(turns).stream()
                .flatMap(t -> t.inconsistencies().stream())
                .toList();
        if (all.isEmpty()) {
            return;
        }
        out.append("## Self-contradictions worth knowing about\n\n");
        for (String problem : all) {
            out.append("- A turn ").append(problem).append('\n');
        }
        out.append("\nThese are reported, not corrected.\n\n");
    }

    private static void appendDecision(StringBuilder out, ClaimLedger ledger, DebateTurn response) {
        out.append("## What you have to decide\n\n");
        List<ClaimLedger.Entry> blocking = ledger.blockingBothSides();
        if (!blocking.isEmpty()) {
            out.append("Both sides agree these block a decision:\n\n");
            for (ClaimLedger.Entry e : blocking) {
                out.append("- ").append(e.summary()).append('\n');
            }
            out.append('\n');
        }
        out.append("The Architect recommends: ").append(response.recommendation()).append("\n\n");
        out.append("Its stated certainty: ").append(response.certainty()).append("\n\n");
        out.append("Nothing here is decided. That is yours.\n\n");
    }

    private static void appendArguments(StringBuilder out, DebateTurn architect,
                                        DebateTurn challenger, DebateTurn response) {
        out.append("## The arguments in full\n\n");
        out.append("<details>\n<summary>Architect, opening</summary>\n\n")
                .append(architect.argument()).append("\n\n</details>\n\n");
        out.append("<details>\n<summary>Challenger</summary>\n\n")
                .append(challenger.argument()).append("\n\n</details>\n\n");
        out.append("<details>\n<summary>Architect, response</summary>\n\n")
                .append(response.argument()).append("\n\n</details>\n");
    }

    private static String label(Convergence convergence) {
        return switch (convergence) {
            case FULL_AGREEMENT -> "full agreement";
            case PARTIAL_AGREEMENT -> "partial agreement";
            case DISAGREEMENT -> "disagreement";
            case POSITION_REVISED -> "a revised position";
        };
    }
}
