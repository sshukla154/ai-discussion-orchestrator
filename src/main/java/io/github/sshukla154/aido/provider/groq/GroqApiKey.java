package io.github.sshukla154.aido.provider.groq;

import java.util.Optional;

/**
 * Resolves the challenger API key, from the environment and nowhere else.
 *
 * <p>This is the only credential the project has, and it exists in a <b>public</b> repository, so
 * the resolution path is deliberately narrow. There is no configuration property for it and no
 * file it can be read from, because both are things that get committed by accident. An environment
 * variable is the one location that cannot be staged.
 *
 * <p>The value is never returned to anything that logs, records or renders. It is read at the
 * moment a request is built, used as a header, and dropped. Nothing stores it in a field, so it
 * cannot reach a heap dump taken later or be printed by a stray {@code toString}.
 *
 * <p>Never add a {@code groq.api-key} property, an {@code .env} default, or a
 * {@code --groq-key} argument. The first two land in tracked files; the third lands in shell
 * history and in the process list, where any other user on the machine can read it.
 */
public final class GroqApiKey {

    /** Matches the name the Groq documentation and its own SDKs use, so it is unsurprising. */
    public static final String ENV_VARIABLE = "GROQ_API_KEY";

    private GroqApiKey() {
    }

    /**
     * @return the key if the environment supplies a non-blank one, otherwise empty. Absence is a
     *         normal condition -- it means the challenger runs manually instead -- so it is not an
     *         error and must not be reported as one.
     */
    public static Optional<String> fromEnvironment() {
        return fromEnvironment(System.getenv(ENV_VARIABLE));
    }

    /** Seam for tests, so no test has to mutate the real environment to cover both branches. */
    static Optional<String> fromEnvironment(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        // Trimmed because a value pasted into a shell profile very often carries a trailing
        // newline or space, and an untrimmed header value fails with an opaque 401.
        return Optional.of(rawValue.trim());
    }

    /**
     * Whether text appears to contain a Groq key, used to keep one out of anything recorded.
     *
     * <p>A prefix check rather than a full-format match: the point is to catch a key that has been
     * pasted somewhere it should not be, and a check that only recognised one exact length would
     * miss the next format. Matching too eagerly here costs nothing, since the only consequence is
     * refusing to record a value that looks like a secret.
     */
    public static boolean looksLikeAKey(String text) {
        return text != null && text.contains("gsk_");
    }
}
