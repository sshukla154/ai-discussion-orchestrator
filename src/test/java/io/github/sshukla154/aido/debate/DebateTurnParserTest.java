package io.github.sshukla154.aido.debate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser is the boundary where an untyped map from a model becomes a value the orchestrator
 * branches on, so every test here is about a wrong value being <em>rejected</em> rather than
 * quietly accepted. A parser that turns a missing field into a plausible default would report
 * green while corrupting the one thing this application exists to state honestly.
 */
class DebateTurnParserTest {

    private final DebateTurnParser parser = new DebateTurnParser();

    /** Every field the schema requires, with values a real turn would plausibly carry. */
    private static Map<String, Object> validTurn() {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("stableKey", "single-writer-ceiling");
        claim.put("claim", "SQLite's single writer bounds throughput before the ORM does");
        claim.put("support", "one connection, so writes serialise regardless of pool size");

        Map<String, Object> disagreement = new LinkedHashMap<>();
        disagreement.put("stableKey", "retry-cost");
        disagreement.put("summary", "whether a failed turn should be retried automatically");
        disagreement.put("whyItMatters", "an automatic retry can duplicate a charged turn");
        disagreement.put("whatWouldResolveIt", "measuring how often a turn completes server-side");
        disagreement.put("blocking", Boolean.TRUE);

        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("argument", "The write path is the constraint, not the mapping layer.");
        turn.put("positionSummary", "Pin the pool to one and stop treating the ORM as the bottleneck.");
        turn.put("claims", List.of(claim));
        turn.put("concessions", List.of());
        turn.put("remainingDisagreements", List.of(disagreement));
        turn.put("openQuestions", List.of("What is the actual write rate under load?"));
        turn.put("convergence", "PARTIAL_AGREEMENT");
        turn.put("convergenceJustification", "We agree on the ceiling, not on the retry policy.");
        turn.put("positionChanged", Boolean.TRUE);
        turn.put("positionChangeSummary", "Dropped the claim that the ORM was the limiting factor.");
        turn.put("acceptOtherPosition", "CONDITIONALLY");
        turn.put("acceptanceConditions", "If retries are proven not to duplicate a charged turn.");
        turn.put("certainty", "Confident about the write path, much less so about retry semantics.");
        turn.put("recommendation", "Measure before choosing a retry policy.");
        turn.put("cannotAssess", Boolean.FALSE);
        turn.put("cannotAssessReason", "");
        return turn;
    }

    private TurnParse parse(Map<String, Object> raw) {
        return parser.parse(Optional.of(raw));
    }

    @Test
    @DisplayName("a complete turn binds every field by component name, with no annotations")
    void parsesACompleteTurn() {
        TurnParse result = parse(validTurn());

        assertThat(result).isInstanceOf(TurnParse.Parsed.class);
        DebateTurn turn = ((TurnParse.Parsed) result).turn();

        assertThat(turn.convergence()).isEqualTo(Convergence.PARTIAL_AGREEMENT);
        assertThat(turn.acceptOtherPosition()).isEqualTo(Acceptance.CONDITIONALLY);
        assertThat(turn.positionChanged()).isTrue();
        assertThat(turn.cannotAssess()).isFalse();
        assertThat(turn.claims()).singleElement()
                .satisfies(c -> assertThat(c.stableKey()).isEqualTo("single-writer-ceiling"));
        assertThat(turn.remainingDisagreements()).singleElement()
                .satisfies(d -> {
                    assertThat(d.stableKey()).isEqualTo("retry-cost");
                    assertThat(d.blocking()).isTrue();
                    assertThat(d.whatWouldResolveIt()).isNotBlank();
                });
        assertThat(turn.openQuestions()).hasSize(1);
    }

    @Test
    @DisplayName("an empty agreement list stays empty rather than becoming a default")
    void doesNotNormaliseEmptyLists() {
        // "We agree on nothing yet" is a real and often correct answer. The schema deliberately
        // has no minItems so it can be expressed, and collapsing it here would erase it.
        TurnParse result = parse(validTurn());

        assertThat(((TurnParse.Parsed) result).turn().concessions()).isEmpty();
    }

    @Test
    @DisplayName("a missing boolean is rejected, not read as false")
    void missingBooleanIsRejected() {
        // The guard that matters most. Both defaults point the wrong way: an absent cannotAssess
        // would read as "an assessment happened", and absent blocking as "safe to decide". If the
        // record used primitive booleans this test would pass while the field silently defaulted.
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.remove("cannotAssess");

        TurnParse result = parse(raw);

        assertThat(result).isInstanceOf(TurnParse.Malformed.class);
        assertThat(((TurnParse.Malformed) result).reason()).contains("cannotAssess");
    }

    @Test
    @DisplayName("an unrecognised enum value is rejected, not read as null")
    void unknownEnumValueIsRejected() {
        // Control decisions come only from typed fields, so a null convergence would be a control
        // decision made by a typo. Jackson can be configured to return null here; it must not be.
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.put("convergence", "MOSTLY_AGREE");

        assertThat(parse(raw)).isInstanceOf(TurnParse.Malformed.class);
    }

    @Test
    @DisplayName("an unknown property is rejected, mirroring additionalProperties:false")
    void unknownPropertyIsRejected() {
        // Without this the schema and the record could drift apart silently, and a field the
        // orchestrator expects to read would simply stop arriving.
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.put("confidenceScore", 0.87);

        TurnParse result = parse(raw);

        assertThat(result).isInstanceOf(TurnParse.Malformed.class);
        assertThat(((TurnParse.Malformed) result).reason()).contains("confidenceScore");
    }

    @Test
    @DisplayName("a malformed payload is kept verbatim so it can be diagnosed")
    void malformedKeepsTheRawPayload() {
        // A turn that ran and cost money is not the same event as one that failed. Discarding the
        // payload here would leave nothing to look at afterwards.
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.remove("convergence");

        TurnParse result = parse(raw);

        assertThat(result).isInstanceOf(TurnParse.Malformed.class);
        assertThat(((TurnParse.Malformed) result).raw())
                .containsEntry("positionSummary", raw.get("positionSummary"));
    }

    @Test
    @DisplayName("no structured output is a distinct outcome from a malformed one")
    void absentStructuredOutputIsItsOwnCase() {
        TurnParse result = parser.parse(Optional.empty());

        assertThat(result).isInstanceOf(TurnParse.Absent.class);
        assertThat(((TurnParse.Absent) result).reason()).contains("--json-schema");
    }

    @Test
    @DisplayName("full agreement alongside open disagreements is reported as a contradiction")
    void detectsFullAgreementContradictingOpenDisagreements() {
        // Convergence is expected to collect at the frictionless middle value, and the opposite
        // error -- claiming agreement while still listing blockers -- is exactly the fabricated
        // consensus this project refuses. Cheaper to catch in code than to prompt against.
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.put("convergence", "FULL_AGREEMENT");

        DebateTurn turn = ((TurnParse.Parsed) parse(raw)).turn();

        assertThat(turn.inconsistencies())
                .anySatisfy(s -> assertThat(s).contains("full agreement"));
    }

    @Test
    @DisplayName("a changed position with no explanation is reported as a contradiction")
    void detectsUnexplainedPositionChange() {
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.put("positionChangeSummary", "  ");

        DebateTurn turn = ((TurnParse.Parsed) parse(raw)).turn();

        assertThat(turn.inconsistencies())
                .anySatisfy(s -> assertThat(s).contains("without saying what changed"));
    }

    @Test
    @DisplayName("a self-consistent turn reports no contradictions")
    void cleanTurnHasNoInconsistencies() {
        // Guards the opposite failure: a checker that flags everything is as useless as one that
        // flags nothing, and would train the reader to ignore it.
        DebateTurn turn = ((TurnParse.Parsed) parse(validTurn())).turn();

        assertThat(turn.inconsistencies()).isEmpty();
    }

    @Test
    @DisplayName("the parsed lists are immutable")
    void listsAreImmutable() {
        DebateTurn turn = ((TurnParse.Parsed) parse(validTurn())).turn();

        assertThatThrownBy(() -> turn.claims().add(
                new Claim("x", "y", "z"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("declining to assess is visible without inspecting the boxed field")
    void declinedToAssessReadsTheRefusalPath() {
        Map<String, Object> raw = new HashMap<>(validTurn());
        raw.put("cannotAssess", Boolean.TRUE);
        raw.put("cannotAssessReason", "The constraints do not say which database is in use.");

        DebateTurn turn = ((TurnParse.Parsed) parse(raw)).turn();

        assertThat(turn.declinedToAssess()).isTrue();
        assertThat(turn.cannotAssessReason()).contains("do not say");
    }
}
