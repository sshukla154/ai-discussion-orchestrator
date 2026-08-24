package io.github.sshukla154.aido.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content hashing for the provenance record.
 *
 * <p>SHA-256 rather than something faster because these values are used as content identities,
 * including inside filenames, and a collision would silently substitute one artifact for another.
 * Hashing a few kilobytes costs microseconds, and a weaker algorithm would also read as a finding
 * in any review of a public repository, for no gain.
 */
public final class Sha256 {

    /** Enough to disambiguate within one run directory; the full digest is the authority. */
    private static final int FILENAME_PREFIX_LENGTH = 8;

    private Sha256() {
    }

    public static String of(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM, so this cannot happen and is not worth a
            // checked exception on every call site.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String ofUtf8(String content) {
        return of(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String filenamePrefix(String hexDigest) {
        return hexDigest.substring(0, FILENAME_PREFIX_LENGTH);
    }
}
