package io.github.sshukla154.aido.common.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The formatter's whole purpose is an ordering guarantee that fails silently when broken, so it
 * gets direct tests rather than being covered sideways through a persistence entity.
 */
class UtcInstantFormatTest {

    @Test
    @DisplayName("every output is exactly the documented width, including whole seconds")
    void alwaysEmitsFixedWidth() {
        // The trailing-zero cases are the ones a formatter that omits them gets wrong, and they
        // are the reason ISO_INSTANT cannot be used here.
        assertThat(UtcInstantFormat.format(Instant.parse("2026-08-24T11:16:43Z")))
                .isEqualTo("2026-08-24T11:16:43.000Z")
                .hasSize(UtcInstantFormat.WIDTH);
        assertThat(UtcInstantFormat.format(Instant.parse("2026-08-24T11:16:43.700Z")))
                .isEqualTo("2026-08-24T11:16:43.700Z")
                .hasSize(UtcInstantFormat.WIDTH);
        assertThat(UtcInstantFormat.format(Instant.parse("2026-01-01T00:00:00.001Z")))
                .isEqualTo("2026-01-01T00:00:00.001Z")
                .hasSize(UtcInstantFormat.WIDTH);
    }

    @Test
    @DisplayName("lexicographic order of the output matches chronological order")
    void textOrderMatchesTimeOrder() {
        // .090 before .100 is the pair that a variable-width format reverses, because "9" sorts
        // after "1". Nothing throws when this is wrong; queries just return the wrong order.
        String earlier = UtcInstantFormat.format(Instant.parse("2026-08-24T11:16:43.090Z"));
        String later = UtcInstantFormat.format(Instant.parse("2026-08-24T11:16:43.100Z"));

        assertThat(earlier).isLessThan(later);
    }

    @Test
    @DisplayName("a millisecond-precision instant round trips exactly")
    void roundTripsAtStorablePrecision() {
        Instant original = Instant.parse("2026-08-24T11:16:43.007Z");

        assertThat(UtcInstantFormat.parse(
                UtcInstantFormat.format(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("toStorablePrecision makes Instant.now round trip, which it otherwise does not")
    void truncationHelperMakesNowRoundTrip() {
        // Instant.now carries microseconds on most JDKs, so storing it and reloading yields a
        // different value. Nothing in the type system says so, which is why the helper exists.
        Instant now = Instant.now();
        Instant storable = UtcInstantFormat.toStorablePrecision(now);

        assertThat(UtcInstantFormat.parse(UtcInstantFormat.format(storable)))
                .isEqualTo(storable);
        assertThat(storable).isEqualTo(now.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("null passes through in both directions")
    void handlesNull() {
        assertThat(UtcInstantFormat.format(null)).isNull();
        assertThat(UtcInstantFormat.parse(null)).isNull();
        assertThat(UtcInstantFormat.parse("   ")).isNull();
    }

    @Test
    @DisplayName("a year that will not fit the fixed width is rejected rather than silently widening")
    void rejectsOutOfRangeYears() {
        // Without this guard the output stops being 24 characters and the ordering guarantee
        // breaks with no error. The realistic way to get here is a unit mistake elsewhere
        // producing a nonsense epoch value.
        assertThatThrownBy(() -> UtcInstantFormat.format(Instant.parse("+10000-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed-width");

        assertThatThrownBy(() -> UtcInstantFormat.format(Instant.ofEpochSecond(-70000000000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed-width");
    }

    @Test
    @DisplayName("a hand-edited stored value fails with the offending text, not an opaque error")
    void malformedStoredValueNamesItself() {
        // The design expects the database to be opened in a viewer, so a hand-edited value is a
        // realistic scenario. Failing is correct; failing without saying which value is not.
        assertThatThrownBy(() -> UtcInstantFormat.parse("yesterday afternoon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yesterday afternoon");
    }
}
