package io.github.sshukla154.aido.provenance;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifier for one debate run, and the name of the directory holding its record.
 *
 * <p>Sortable, because the timestamp is fixed width, so listing a directory yields runs in
 * chronological order and manual retention is a glob rather than a script. It deliberately does
 * not reuse {@code UtcInstantFormat}: that format contains colons, which are illegal in a Windows
 * path component. The compact form here drops the separators and the millisecond field.
 *
 * <p>The random suffix exists because two runs can start in the same second. Without it that is a
 * collision; with it, it is a non-event.
 *
 * <p>Precision is seconds and this is an identifier that happens to sort. Nothing should parse it
 * back into a time -- the authoritative instant is recorded separately, at full precision.
 */
public record RunId(String value) {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /**
     * Anchored, and doing more work than it looks like. Alongside the shape, it rules out
     * {@code ..}, path separators, trailing dots and spaces, and the Windows reserved device names
     * -- none of which can start with a digit. A {@code RunId} that exists is safe to use as a
     * path component, which is the only property the filesystem code should have to trust.
     */
    private static final Pattern VALID = Pattern.compile("^\\d{8}T\\d{6}Z-[0-9a-f]{8}$");

    private static final int SUFFIX_LENGTH = 8;

    public RunId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "not a valid run id (expected yyyyMMddTHHmmssZ-8hex): " + value);
        }
    }

    public static RunId mint(Instant startedAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, SUFFIX_LENGTH);
        return new RunId(TIMESTAMP.format(startedAt) + "-" + suffix);
    }

    @Override
    public String toString() {
        return value;
    }
}
