package io.github.sshukla154.aido.common.time;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Fixed-width ISO-8601 UTC text representation of an {@link Instant}.
 *
 * <p>Deliberately free of any persistence dependency. The invariant it protects is about
 * ordering, not about storage, so it belongs in the domain: any two formatted values compare
 * lexicographically in the same order as the instants they came from. A JPA adapter can sit on
 * top of this when there is a database to talk to, but nothing here needs one.
 *
 * <p>Text is chosen over epoch milliseconds because this is a tool that will be debugged by
 * opening a database file in a viewer, and {@code 2026-08-24T11:16:43.007Z} is legible where
 * {@code 1787915803007} is not.
 *
 * <p><b>The fixed width is what makes the ordering guarantee true.</b> The millisecond field is
 * always three digits, because otherwise {@code ...:00.9Z} sorts <em>after</em>
 * {@code ...:00.10Z} and every {@code ORDER BY} over the value is quietly wrong with nothing
 * thrown. {@link DateTimeFormatter#ISO_INSTANT} drops trailing zeros, so it cannot be used.
 *
 * <p><b>Precision is milliseconds.</b> {@code Instant.now()} carries microseconds on most JDKs,
 * so a value formatted and parsed back is not {@code equals} to the original unless it was
 * truncated first. Use {@link #toStorablePrecision(Instant)} at the point of capture.
 *
 * <p>Ordering of domain records still uses an explicit sequence number rather than a timestamp:
 * two records can share a millisecond, so time is not an identity.
 */
public final class UtcInstantFormat {

    /** Always 24 characters: {@code yyyy-MM-ddTHH:mm:ss.SSSZ}. */
    public static final int WIDTH = 24;

    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /**
     * The range a four-digit year field can represent.
     *
     * <p>Outside it the output stops being {@link #WIDTH} characters, which silently breaks the
     * ordering guarantee. Rather than allow that, an out-of-range value is rejected.
     */
    private static final int MIN_YEAR = 1;
    private static final int MAX_YEAR = 9999;

    private UtcInstantFormat() {
    }

    /**
     * Truncates to the precision this format can represent.
     *
     * <p>Use at the point an instant is captured, so a value read back equals the one written.
     * Without it {@code Instant.now()} loses its sub-millisecond digits on the way out and every
     * later comparison against the in-memory original fails.
     */
    public static Instant toStorablePrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    public static String format(Instant instant) {
        if (instant == null) {
            return null;
        }
        int year = instant.atZone(ZoneOffset.UTC).getYear();
        if (year < MIN_YEAR || year > MAX_YEAR) {
            // Loud rather than silent. A year outside this range would change the text width and
            // break lexicographic ordering, and the likeliest way to produce one is a unit error
            // elsewhere yielding a nonsense epoch value -- worth failing on.
            throw new IllegalArgumentException(
                    "instant year " + year + " cannot be stored in fixed-width form (expected "
                            + MIN_YEAR + ".." + MAX_YEAR + "): " + instant);
        }
        return FORMAT.format(instant);
    }

    public static Instant parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            // The design expects this text to live in a file a person may open and edit, so a
            // hand-mangled value is realistic. Failing is right; failing without naming the
            // offending value is not.
            throw new IllegalArgumentException(
                    "not a valid ISO-8601 instant: '" + text + "'", e);
        }
    }
}
