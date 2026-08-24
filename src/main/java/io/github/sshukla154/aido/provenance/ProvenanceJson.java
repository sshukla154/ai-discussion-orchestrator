package io.github.sshukla154.aido.provenance;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper used for provenance files, deliberately not the application's.
 *
 * <p>Its configuration is part of the on-disk file format, so it must not drift with a mapper
 * someone else tunes for an unrelated reason. Indented because a human reads these by hand, and
 * nulls are written rather than omitted: for a forensic record {@code "httpStatus": null} means
 * "recorded, no value", which an absent key does not.
 */
final class ProvenanceJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private ProvenanceJson() {
    }

    static String toPrettyJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
