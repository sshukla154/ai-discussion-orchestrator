package io.github.sshukla154.aido.persistence;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import io.github.sshukla154.aido.common.time.UtcInstantFormat;

/**
 * JPA adapter over {@link UtcInstantFormat}.
 *
 * <p>Test-scoped for now, and that is the point: phase one has no database, so nothing in main
 * sources may depend on {@code jakarta.persistence} -- a rule the architecture test enforces.
 * The formatting invariant itself is domain logic and lives in main; only this thin adapter is
 * waiting for a persistence layer to exist.
 *
 * <p>Moves to main sources when the real entities arrive, at which point it should carry no
 * logic beyond the two delegating calls below.
 */
@Converter(autoApply = true)
public class UtcInstantAttributeConverter implements AttributeConverter<Instant, String> {

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return UtcInstantFormat.format(attribute);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return UtcInstantFormat.parse(dbData);
    }
}
