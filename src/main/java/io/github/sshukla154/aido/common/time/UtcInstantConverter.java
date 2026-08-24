package io.github.sshukla154.aido.common.time;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores an {@link Instant} as fixed-width ISO-8601 UTC text.
 *
 * <p>SQLite has no temporal type, so something has to choose the on-disk representation. Text is
 * chosen over epoch milliseconds because this is a tool that will be debugged by opening the
 * database in a viewer, and {@code 2026-08-24T11:16:43.007Z} is legible where
 * {@code 1787915803007} is not.
 *
 * <p><b>The fixed width is not cosmetic.</b> Text timestamps sort lexicographically, so the
 * millisecond field must always be three digits. Without the padding, {@code ...:00.9Z} sorts
 * <em>after</em> {@code ...:00.10Z}, and every {@code ORDER BY} and range comparison over the
 * column becomes subtly wrong in a way nothing reports.
 * {@link DateTimeFormatter#ISO_INSTANT} omits trailing zeros, so it cannot be used here.
 *
 * <p><b>Precision is milliseconds.</b> {@code Instant.now()} carries microseconds on most JDKs,
 * so a value stored and reloaded is not {@code equals} to the original unless it was truncated
 * first. Call {@link #toStorablePrecision(Instant)} before handing an instant to an entity, and
 * comparisons stay exact.
 *
 * <p>Ordering of domain records still uses an explicit sequence column rather than a timestamp:
 * two rows can share a millisecond, so time is not a key.
 */
@Converter(autoApply = true)
public class UtcInstantConverter implements AttributeConverter<Instant, String> {

    /** Always 24 characters: {@code yyyy-MM-ddTHH:mm:ss.SSSZ}. */
    public static final int WIDTH = 24;

    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /**
     * The four-digit year field can only represent this range.
     *
     * <p>Outside it the output stops being 24 characters wide, which silently breaks the
     * ordering guarantee above. Rather than let that happen, an out-of-range value is rejected.
     */
    private static final int MIN_YEAR = 1;
    private static final int MAX_YEAR = 9999;

    /**
     * Truncates to the precision this converter can actually store.
     *
     * <p>Use it wherever an instant is captured, so that a value read back from the database
     * equals the one written. Without it, {@code Instant.now()} loses its sub-millisecond digits
     * on the way to storage and every later comparison against the in-memory original fails.
     */
    public static Instant toStorablePrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        if (attribute == null) {
            return null;
        }
        int year = attribute.atZone(ZoneOffset.UTC).getYear();
        if (year < MIN_YEAR || year > MAX_YEAR) {
            // Loud rather than silent. A year outside this range would widen or narrow the text
            // and break lexicographic ordering, and the most likely way to get one is a unit
            // error elsewhere producing a nonsense epoch value -- worth failing on.
            throw new IllegalArgumentException(
                    "instant year " + year + " cannot be stored in fixed-width form (expected "
                            + MIN_YEAR + ".." + MAX_YEAR + "): " + attribute);
        }
        return FORMAT.format(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(dbData);
        } catch (RuntimeException e) {
            // The database is a local file the design explicitly expects to be opened in a
            // viewer, so a hand-edited value is a realistic way to get here. Failing is right;
            // failing without saying which value is not.
            throw new IllegalArgumentException(
                    "stored timestamp is not a valid ISO-8601 instant: '" + dbData + "'", e);
        }
    }
}
