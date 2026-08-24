package io.github.sshukla154.aido.common.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The converter was previously covered only sideways, through an entity in the persistence spike.
 * Its whole purpose is an ordering guarantee that fails silently when broken, so it deserves
 * direct tests.
 */
class UtcInstantConverterTest {

    private final UtcInstantConverter converter = new UtcInstantConverter();

    @Test
    @DisplayName("every output is exactly the documented width, including whole seconds")
    void alwaysEmitsFixedWidth() {
        // The trailing-zero cases are the ones a formatter that omits them gets wrong, and they
        // are the reason ISO_INSTANT cannot be used here.
        assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-08-24T11:16:43Z")))
                .isEqualTo("2026-08-24T11:16:43.000Z")
                .hasSize(UtcInstantConverter.WIDTH);
        assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-08-24T11:16:43.700Z")))
                .isEqualTo("2026-08-24T11:16:43.700Z")
                .hasSize(UtcInstantConverter.WIDTH);
        assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-01-01T00:00:00.001Z")))
                .isEqualTo("2026-01-01T00:00:00.001Z")
                .hasSize(UtcInstantConverter.WIDTH);
    }

    @Test
    @DisplayName("lexicographic order of the output matches chronological order")
    void textOrderMatchesTimeOrder() {
        // .090 before .100 is the pair that a variable-width format reverses, because "9" sorts
        // after "1". Nothing throws when this is wrong; queries just return the wrong order.
        String earlier = converter.convertToDatabaseColumn(Instant.parse("2026-08-24T11:16:43.090Z"));
        String later = converter.convertToDatabaseColumn(Instant.parse("2026-08-24T11:16:43.100Z"));

        assertThat(earlier).isLessThan(later);
    }

    @Test
    @DisplayName("a millisecond-precision instant round trips exactly")
    void roundTripsAtStorablePrecision() {
        Instant original = Instant.parse("2026-08-24T11:16:43.007Z");

        assertThat(converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("toStorablePrecision makes Instant.now round trip, which it otherwise does not")
    void truncationHelperMakesNowRoundTrip() {
        // Instant.now carries microseconds on most JDKs, so storing it and reloading yields a
        // different value. Nothing in the type system says so, which is why the helper exists.
        Instant now = Instant.now();
        Instant storable = UtcInstantConverter.toStorablePrecision(now);

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(storable)))
                .isEqualTo(storable);
        assertThat(storable).isEqualTo(now.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("null passes through in both directions")
    void handlesNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
    }

    @Test
    @DisplayName("a year that will not fit the fixed width is rejected rather than silently widening")
    void rejectsOutOfRangeYears() {
        // Without this guard the output stops being 24 characters and the ordering guarantee
        // breaks with no error. The realistic way to get here is a unit mistake elsewhere
        // producing a nonsense epoch value.
        assertThatThrownBy(() -> converter.convertToDatabaseColumn(Instant.parse("+10000-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed-width");

        assertThatThrownBy(() -> converter.convertToDatabaseColumn(Instant.ofEpochSecond(-70000000000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed-width");
    }

    @Test
    @DisplayName("a hand-edited stored value fails with the offending text, not an opaque error")
    void malformedStoredValueNamesItself() {
        // The design expects the database to be opened in a viewer, so a hand-edited value is a
        // realistic scenario. Failing is correct; failing without saying which value is not.
        assertThatThrownBy(() -> converter.convertToEntityAttribute("yesterday afternoon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yesterday afternoon");
    }
}
