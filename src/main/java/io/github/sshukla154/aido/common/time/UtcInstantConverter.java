package io.github.sshukla154.aido.common.time;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores an {@link Instant} as fixed-width ISO-8601 UTC text.
 *
 * <p>SQLite has no temporal type, so something has to decide the on-disk representation. Text
 * is chosen over epoch milliseconds because this is a tool that will be debugged by opening the
 * database in a viewer, and {@code 2026-08-24T11:16:43.007Z} is legible where {@code 1787915803007}
 * is not.
 *
 * <p><b>The fixed width is not cosmetic.</b> Text timestamps sort lexicographically, so the
 * millisecond field must always be three digits. Without the padding,
 * {@code ...:00.9Z} sorts <em>after</em> {@code ...:00.10Z}, and every {@code ORDER BY} and
 * range comparison over the column becomes subtly wrong in a way no exception reports.
 * {@link DateTimeFormatter#ISO_INSTANT} omits trailing zeros, so it cannot be used here.
 *
 * <p>Ordering of domain records still uses an explicit sequence column rather than a timestamp:
 * two rows can share a millisecond, so time is not a key.
 */
@Converter(autoApply = true)
public class UtcInstantConverter implements AttributeConverter<Instant, String> {

    /** Always 24 characters: {@code yyyy-MM-ddTHH:mm:ss.SSSZ}. */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    public static final int WIDTH = 24;

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : FORMAT.format(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return Instant.parse(dbData);
    }
}
