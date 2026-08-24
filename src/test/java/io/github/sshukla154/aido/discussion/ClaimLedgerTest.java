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
 * The ledger is where a discussion could quietly appear to settle itself, so these tests are
 * mostly about what must <em>not</em> happen: a point disappearing because nobody mentioned it, or
 * one side's opinion being enough to gate a decision.
 */
class ClaimLedgerTest {

    @Test
    @DisplayName("a point nobody mentions again stays open, because silence is not agreement")
    void absenceDoesNotResolve() {
        // The failure this prevents is invisible: if a turn's omissions retired entries, the ledger
        // would empty out over a few rounds and the discussion would look settled while nothing had
        // actually been agreed.
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withClaims(claim("retry-cost", "retries may duplicate a charged turn"))
                        .build(), Participant.ARCHITECT, 1)
                .fold(turn().build(), Participant.CHALLENGER, 2);

        assertThat(ledger.open()).extracting(ClaimLedger.Entry::stableKey).containsExactly("retry-cost");
        assertThat(ledger.conceded()).isEmpty();
    }

    @Test
    @DisplayName("only an explicit concession retires a point")
    void concessionResolves() {
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withClaims(claim("retry-cost", "retries may duplicate a charged turn"))
                        .build(), Participant.ARCHITECT, 1)
                .fold(turn().withConcessions(new Concession("retry-cost",
                        "retries can duplicate a charged turn", "the duplicate-turn argument"))
                        .build(), Participant.CHALLENGER, 2);

        assertThat(ledger.conceded()).extracting(ClaimLedger.Entry::stableKey).containsExactly("retry-cost");
        assertThat(ledger.open()).isEmpty();
    }

    @Test
    @DisplayName("one side calling a point blocking is not enough to gate a decision")
    void blockingNeedsBothSides() {
        // A participant is being asked whether a human may responsibly proceed, and models
        // over-report that. If a single flag gated the outcome, the gate would be closed almost
        // always and stop carrying information.
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withDisagreements(disagreement("rebalance-cost", true))
                        .build(), Participant.ARCHITECT, 1);

        assertThat(ledger.open()).hasSize(1);
        assertThat(ledger.blockingBothSides()).isEmpty();
    }

    @Test
    @DisplayName("both sides calling the same point blocking does gate a decision")
    void blockingByBothSidesCounts() {
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withDisagreements(disagreement("rebalance-cost", true))
                        .build(), Participant.ARCHITECT, 1)
                .fold(turn().withDisagreements(disagreement("rebalance-cost", true))
                        .build(), Participant.CHALLENGER, 2);

        assertThat(ledger.blockingBothSides())
                .extracting(ClaimLedger.Entry::stableKey).containsExactly("rebalance-cost");
    }

    @Test
    @DisplayName("the same side flagging twice does not count as agreement between sides")
    void repeatedFlagFromOneSideIsStillOneSide() {
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withDisagreements(disagreement("rebalance-cost", true))
                        .build(), Participant.ARCHITECT, 1)
                .fold(turn().withDisagreements(disagreement("rebalance-cost", true))
                        .build(), Participant.ARCHITECT, 3);

        assertThat(ledger.blockingBothSides()).isEmpty();
    }

    @Test
    @DisplayName("a restated point keeps its original wording rather than drifting")
    void restatementDoesNotOverwriteTheSummary() {
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withClaims(claim("retry-cost", "the original phrasing"))
                        .build(), Participant.ARCHITECT, 1)
                .fold(turn().withClaims(claim("retry-cost", "a later paraphrase of the same point"))
                        .build(), Participant.CHALLENGER, 2);

        assertThat(ledger.open()).singleElement()
                .satisfies(e -> {
                    assertThat(e.summary()).isEqualTo("the original phrasing");
                    assertThat(e.firstSeenTurn()).isEqualTo(1);
                    assertThat(e.lastTouchedTurn()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("a paraphrased key forks the point, which is the known cost of not guessing")
    void paraphrasedKeyForks() {
        // Documented rather than compensated for. Matching by similarity could merge two genuinely
        // different points and erase one, which is worse than a visible duplicate: a fork is
        // cosmetic, a silent merge rewrites what someone said.
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withClaims(claim("retry-cost", "retries may duplicate a turn"))
                        .build(), Participant.ARCHITECT, 1)
                .fold(turn().withClaims(claim("cost-of-retries", "retries may duplicate a turn"))
                        .build(), Participant.CHALLENGER, 2);

        assertThat(ledger.open()).hasSize(2);
    }

    @Test
    @DisplayName("a concession for an unrecorded point is still kept")
    void concessionWithoutAPriorClaimIsRecorded() {
        // The other side may have made the point in prose without listing it as a claim. Dropping
        // the concession for failing to match would lose the one signal that a mind changed.
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withConcessions(new Concession("latency-budget",
                        "cross-region reads will be slower", "the latency argument"))
                        .build(), Participant.ARCHITECT, 1);

        assertThat(ledger.conceded()).extracting(ClaimLedger.Entry::stableKey)
                .containsExactly("latency-budget");
    }

    @Test
    @DisplayName("the rendered block carries the meaning, not just the slug")
    void renderIncludesSummaries() {
        // A model asked to reuse a key from a list of bare slugs paraphrases instead. Offering the
        // point in words is what makes reuse plausible at all.
        ClaimLedger ledger = ClaimLedger.empty()
                .fold(turn().withClaims(claim("retry-cost", "retries may duplicate a charged turn"))
                        .build(), Participant.ARCHITECT, 1);

        assertThat(ledger.render())
                .contains("retry-cost")
                .contains("retries may duplicate a charged turn")
                .contains("[open]");
    }

    @Test
    @DisplayName("an empty ledger renders an explicit placeholder, not a blank")
    void emptyLedgerRendersPlaceholder() {
        assertThat(ClaimLedger.empty().render()).isEqualTo("none yet");
        assertThat(ClaimLedger.empty().isEmpty()).isTrue();
    }

    private static Claim claim(String key, String text) {
        return new Claim(key, text, "because of the reasoning above");
    }

    private static Disagreement disagreement(String key, boolean blocking) {
        return new Disagreement(key, "whether " + key + " is affordable",
                "an unaffordable one makes the scheme unusable", "measure it once", blocking);
    }

    private static TurnBuilder turn() {
        return new TurnBuilder();
    }

    /** Keeps each test to the one or two fields it is actually about. */
    private static final class TurnBuilder {

        private List<Claim> claims = List.of();
        private List<Concession> concessions = List.of();
        private List<Disagreement> disagreements = List.of();

        TurnBuilder withClaims(Claim... values) {
            this.claims = List.of(values);
            return this;
        }

        TurnBuilder withConcessions(Concession... values) {
            this.concessions = List.of(values);
            return this;
        }

        TurnBuilder withDisagreements(Disagreement... values) {
            this.disagreements = List.of(values);
            return this;
        }

        DebateTurn build() {
            return new DebateTurn("argument", "position", claims, concessions, disagreements,
                    List.of(), Convergence.PARTIAL_AGREEMENT, "because", Boolean.FALSE, "",
                    Acceptance.NO, "", "fairly sure", "measure it", Boolean.FALSE, "");
        }
    }
}
