package io.github.sshukla154.aido.provider.claude;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds the Claude Code CLI without hardcoding an install location.
 *
 * <p>The install path lives under a user home directory, which makes it both
 * machine-specific and identifying. Committing one would break the redaction policy, so the
 * path is discovered at runtime: an explicit override first, then a PATH scan.
 *
 * <p>The executable is a native console binary, not a {@code .cmd} shim or a script, so it is
 * spawned directly. Wrapping it in {@code cmd.exe /c} would reintroduce the whole family of
 * batch-shim argument-mangling problems for no benefit.
 */
public final class ClaudeCliLocator {

    /** Checked before PATH, so a non-standard install needs no code change. */
    public static final String OVERRIDE_PROPERTY = "aido.claude.executable";
    public static final String OVERRIDE_ENV = "AIDO_CLAUDE_EXECUTABLE";

    private static final List<String> CANDIDATE_NAMES = List.of("claude.exe", "claude");

    private ClaudeCliLocator() {
    }

    /**
     * @return the resolved executable, or empty when the CLI is not installed. Absence is a
     *         normal condition on a CI runner and must not be an exception.
     */
    public static Optional<Path> locate() {
        Optional<Path> override = fromOverride();
        if (override.isPresent()) {
            return override;
        }
        return fromPath();
    }

    public static Path require() {
        return locate().orElseThrow(() -> new IllegalStateException(
                "Claude Code CLI not found. Install it, or set -D" + OVERRIDE_PROPERTY
                        + " / " + OVERRIDE_ENV + " to its full path."));
    }

    private static Optional<Path> fromOverride() {
        String raw = System.getProperty(OVERRIDE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(OVERRIDE_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Path p = Path.of(raw.strip());
        return isExecutableFile(p) ? Optional.of(p.toAbsolutePath()) : Optional.empty();
    }

    private static Optional<Path> fromPath() {
        String pathVar = System.getenv("PATH");
        if (pathVar == null || pathVar.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathVar.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            for (String name : CANDIDATE_NAMES) {
                try {
                    Path candidate = Path.of(entry.strip(), name);
                    if (isExecutableFile(candidate)) {
                        return Optional.of(candidate.toAbsolutePath());
                    }
                } catch (InvalidPathException e) {
                    // A malformed PATH entry is not worth failing over; skip it.
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }
}
