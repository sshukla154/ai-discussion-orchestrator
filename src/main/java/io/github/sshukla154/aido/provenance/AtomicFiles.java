package io.github.sshukla154.aido.provenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Filesystem writes that cannot leave a half-written record behind.
 *
 * <p>Everything is written to a sibling temporary file and then moved into place atomically. A
 * reader therefore never observes a partial file, and a process killed mid-write leaves a
 * {@code .tmp} that is self-evidently junk rather than a truncated {@code prompt.txt} that looks
 * complete. Leftover temporary files are deliberately not cleaned up: they mark where the
 * interruption happened.
 *
 * <p>No {@code fsync}. The failure this guards against is process death -- an operator killing an
 * application that appears hung is an anticipated event here -- and page-cache contents survive
 * that. Only power loss defeats it, and against power loss the ordering guarantee would not hold
 * either. A stated limitation rather than an oversight.
 */
public final class AtomicFiles {

    private static final String TEMP_SUFFIX = ".tmp";

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content) throws IOException {
        writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes exactly these bytes.
     *
     * <p>Callers that also hash the content must pass the same array they hashed. Encoding the
     * string here instead would let the stored bytes and the hashed bytes differ by a charset
     * decision, which would quietly falsify the claim that the hash identifies the file.
     */
    static void writeBytes(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        Files.write(temp, content);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Not every filesystem supports an atomic move. A non-atomic replace is still better
            // than leaving the temporary file and no record at all.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes only if absent, for content-addressed files whose name already encodes their content.
     * Two writers producing the same name are producing the same bytes, so a race is harmless.
     */
    static void writeIfAbsent(Path target, byte[] content) throws IOException {
        if (Files.notExists(target)) {
            writeBytes(target, content);
        }
    }
}
