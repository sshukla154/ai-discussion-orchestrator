package io.github.sshukla154.aido.debate;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts a provider's {@code structured_output} map into a {@link DebateTurn}.
 *
 * <p>Holds its own mapper rather than using an application-wide one, because strictness here is a
 * correctness requirement of the debate contract rather than a preference, and it must not drift
 * with someone else's configuration.
 *
 * <p>Nothing disables strict enum handling. Jackson 3 moved that switch off
 * {@link DeserializationFeature} and defaults to throwing on an unrecognised value, which is what
 * this parser needs: a null convergence would be a control decision made by a typo. That default
 * is verified by a test rather than restated here, because defaults change between major versions.
 */
@Component
public final class DebateTurnParser {

    private final ObjectMapper mapper = JsonMapper.builder()
            // The Java-side mirror of additionalProperties:false in the schema: a key the record
            // does not know about means the two have diverged, which is worth failing on rather
            // than dropping silently. Set explicitly because Jackson 3 changed the default.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public TurnParse parse(Optional<Map<String, Object>> structuredOutput) {
        if (structuredOutput.isEmpty()) {
            return new TurnParse.Absent(
                    "the provider returned no structured output; was --json-schema supplied?");
        }
        Map<String, Object> raw = structuredOutput.get();
        try {
            return new TurnParse.Parsed(mapper.convertValue(raw, DebateTurn.class));
        } catch (JacksonException e) {
            // Unchecked in Jackson 3, so this would otherwise escape as a runtime failure and
            // lose the payload along with the turn.
            return new TurnParse.Malformed(describe(e.getOriginalMessage()), raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            // Thrown by the record's own canonical constructor: a required field was absent, an
            // enum was null, or a list contained a null element.
            return new TurnParse.Malformed(describe(e.getMessage()), raw);
        }
    }

    private String describe(String message) {
        return message == null || message.isBlank()
                ? "structured output did not match the turn schema"
                : "structured output did not match the turn schema: " + message;
    }
}
