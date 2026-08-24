package io.github.sshukla154.aido.discussion;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.sshukla154.aido.debate.Acceptance;
import io.github.sshukla154.aido.debate.Claim;
import io.github.sshukla154.aido.debate.Concession;
import io.github.sshukla154.aido.debate.Convergence;
import io.github.sshukla154.aido.debate.DebateTurn;
import io.github.sshukla154.aido.debate.Disagreement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artifact is the product, so these tests are about what it must never claim. Chiefly: that
 * agreement neither participant stated does not appear, and that a decision is never presented as
 * already made.
 */
class DiscussionArtifactTest {

    private static final DiscussionQuestion QUESTION = new DiscussionQuestion(
            "Shard by tenant or by region?", "Pick one and know the trade.",
            List.of("single-writer SQLite"));

    @Test
    @DisplayName("full agreement is only reported when both sides independently say so")
    void bothSidesMustAgree() {
        String out = render(turn(Convergence.FULL_AGREEMENT), turn(Convergence.FULL_AGREEMENT));

        assertThat(out).contains("Both participants independently report full agreement.");
    }

    @Test
    @DisplayName("one side claiming agreement while the other disagrees is reported as that gap")
    void contradictoryReportsAreTheFinding() {
        // The single most important line in the file. Averaging these into a middle value neither
        // participant stated would fabricate the one thing this application exists to report
        // honestly, and it would look entirely reasonable on the page.
        String out = render(turn(Convergence.FULL_AGREEMENT), turn(Convergence.DISAGREEMENT));

        assertThat(out).contains("disagree about whether they agree");
        assertThat(out).doesNotContain("Both participants independently report full agreement");
    }

    @Test
    @DisplayName("partial agreement is never reported as full")
    void partialIsNotFull() {
        String out = render(turn(Convergence.PARTIAL_AGREEMENT), turn(Convergence.PARTIAL_AGREEMENT));

        assertThat(out).contains("No claim of full agreement from either side.");
    }

    @Test
    @DisplayName("the document always ends by handing the decision back")
    void neverDecides() {
        String out = render(turn(Convergence.FULL_AGREEMENT), turn(Convergence.FULL_AGREEMENT));

        assertThat(out).contains("What you have to decide");
        assertThat(out).contains("Nothing here is decided. That is yours.");
    }

    @Test
    @DisplayName("an empty settled section says so rather than being omitted")
    void statesWhenNothingWasConceded() {
        // An absent section reads as an oversight. Stating it reads as a result, and agreeing on
        // nothing after one round is entirely normal.
        String out = render(turn(Convergence.DISAGREEMENT), turn(Convergence.DISAGREEMENT));

        assertThat(out).contains("Nothing was conceded by either side in this round.");
    }

    @Test
    @DisplayName("an open point carries why it matters and what would settle it")
    void openPointsAreActionable() {
        // Without these a disagreement outcome is "they disagreed", which tells the reader nothing
        // they can act on.
        String out = render(turn(Convergence.DISAGREEMENT), turn(Convergence.DISAGREEMENT));

        assertThat(out).contains("Why it matters:");
        assertThat(out).contains("What would settle it:");
    }

    @Test
    @DisplayName("a self-contradictory turn is surfaced, not quietly corrected")
    void surfacesInconsistencies() {
        DebateTurn contradictory = new DebateTurn("argument", "position",
                List.of(), List.of(), List.of(disagreement()), List.of(),
                Convergence.FULL_AGREEMENT, "because", Boolean.FALSE, "",
                Acceptance.NO, "", "sure", "measure", Boolean.FALSE, "");

        String out = render(contradictory, turn(Convergence.PARTIAL_AGREEMENT));

        assertThat(out).contains("Self-contradictions worth knowing about");
        assertThat(out).contains("These are reported, not corrected.");
    }

    @Test
    @DisplayName("a point both sides call blocking is named under the decision")
    void blockingByBothSidesIsCalledOut() {
        DebateTurn challenger = turn(Convergence.DISAGREEMENT);
        DebateTurn response = turn(Convergence.DISAGREEMENT);
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(challenger, Participant.CHALLENGER, 2)
                .fold(response, Participant.ARCHITECT, 3);

        String out = DiscussionArtifact.render(QUESTION, ledger,
                turn(Convergence.PARTIAL_AGREEMENT), challenger, response, "20260824T101533Z-7f3a91c4");

        assertThat(out).contains("Both sides agree these block a decision");
    }

    @Test
    @DisplayName("the full arguments are kept, and the run is named so the record is findable")
    void keepsArgumentsAndNamesTheRun() {
        String out = render(turn(Convergence.PARTIAL_AGREEMENT), turn(Convergence.PARTIAL_AGREEMENT));

        assertThat(out).contains("The arguments in full");
        assertThat(out).contains("20260824T101533Z-7f3a91c4");
    }

    private static String render(DebateTurn challenger, DebateTurn response) {
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(challenger, Participant.CHALLENGER, 2)
                .fold(response, Participant.ARCHITECT, 3);
        return DiscussionArtifact.render(QUESTION, ledger, turn(Convergence.PARTIAL_AGREEMENT),
                challenger, response, "20260824T101533Z-7f3a91c4");
    }

    private static DebateTurn turn(Convergence convergence) {
        return new DebateTurn(
                "the argument in full, at length",
                "the position in a sentence",
                List.of(new Claim("rebalance-cost", "rebalancing is affordable", "because measured")),
                convergence == Convergence.FULL_AGREEMENT
                        ? List.of(new Concession("latency", "reads are slower", "the latency argument"))
                        : List.of(),
                convergence == Convergence.FULL_AGREEMENT ? List.of() : List.of(disagreement()),
                List.of("what is the tenant size distribution?"),
                convergence, "because of the above", Boolean.FALSE, "",
                Acceptance.CONDITIONALLY, "if measured", "reasonably sure",
                "measure a rebalance first", Boolean.FALSE, "");
    }

    private static Disagreement disagreement() {
        return new Disagreement("rebalance-cost", "whether rebalancing is affordable",
                "an unaffordable rebalance makes the scheme unusable",
                "measure a rebalance of the largest tenant", Boolean.TRUE);
    }
}
