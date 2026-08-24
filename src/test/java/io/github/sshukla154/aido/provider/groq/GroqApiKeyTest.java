package io.github.sshukla154.aido.provider.groq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroqApiKeyTest {

    @Test
    @DisplayName("a blank or absent value is empty, because running without a key is normal")
    void absenceIsNotAnError() {
        // The challenger runs manually unless a key is present, so absence must be an ordinary
        // condition rather than something the caller has to catch.
        assertThat(GroqApiKey.fromEnvironment(null)).isEmpty();
        assertThat(GroqApiKey.fromEnvironment("")).isEmpty();
        assertThat(GroqApiKey.fromEnvironment("   ")).isEmpty();
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed, since a pasted key usually carries a newline")
    void trimsPastedWhitespace() {
        // An untrimmed header value fails with an opaque 401, which is a miserable thing to debug.
        assertThat(GroqApiKey.fromEnvironment("  gsk_value\n")).contains("gsk_value");
    }

    @Test
    @DisplayName("key-shaped text is recognised so it can be kept out of anything recorded")
    void recognisesKeyShapedText() {
        assertThat(GroqApiKey.looksLikeAKey("bad header: Bearer gsk_abc123")).isTrue();
        assertThat(GroqApiKey.looksLikeAKey("rate limit reached")).isFalse();
        assertThat(GroqApiKey.looksLikeAKey(null)).isFalse();
    }
}
