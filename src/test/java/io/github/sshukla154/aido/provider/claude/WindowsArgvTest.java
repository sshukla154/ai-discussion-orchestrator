package io.github.sshukla154.aido.provider.claude;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The escaping is asserted directly rather than only through a spawned process, because the
 * backslash-run rules have edge cases that a single happy-path round trip would not reach.
 */
class WindowsArgvTest {

    @Test
    @DisplayName("a value with no quotes is left exactly as it is")
    void leavesQuotelessValuesAlone() {
        assertThat(WindowsArgv.escapeForWindowsChild("sonnet")).isEqualTo("sonnet");
        assertThat(WindowsArgv.escapeForWindowsChild("C:\\tmp\\path")).isEqualTo("C:\\tmp\\path");
        assertThat(WindowsArgv.escapeForWindowsChild("")).isEmpty();
    }

    @Test
    @DisplayName("each quote becomes a backslash-quote pair")
    void escapesQuotes() {
        assertThat(WindowsArgv.escapeForWindowsChild("{\"a\":1}")).isEqualTo("{\\\"a\\\":1}");
    }

    @Test
    @DisplayName("a backslash run before a quote is doubled, so it survives as literal text")
    void doublesBackslashRunsPrecedingAQuote() {
        // Input:  \"   -> one backslash then a quote
        // Output: \\\" -> two backslashes (one literal backslash) then an escaped quote
        assertThat(WindowsArgv.escapeForWindowsChild("\\\"")).isEqualTo("\\\\\\\"");
        // Two backslashes before a quote are doubled to four, then the escaped quote
        // contributes a fifth.
        assertThat(WindowsArgv.escapeForWindowsChild("\\\\\"")).isEqualTo("\\\\\\\\\\\"");
    }

    @Test
    @DisplayName("backslashes not adjacent to a quote are untouched")
    void leavesUnrelatedBackslashesAlone() {
        // A Windows path inside a JSON string value: the path separators must not be doubled,
        // only the run that directly precedes a quote.
        assertThat(WindowsArgv.escapeForWindowsChild("{\"p\":\"C:\\tmp\"}"))
                .isEqualTo("{\\\"p\\\":\\\"C:\\tmp\\\"}");
    }

    @Test
    @DisplayName("a trailing backslash run is preserved when there is no quote to protect")
    void preservesTrailingBackslashesWithoutQuotes() {
        // No quote means the early return leaves the value alone, so the run survives intact.
        assertThat(WindowsArgv.escapeForWindowsChild("C:\tmp\\\\")).isEqualTo("C:\tmp\\\\");
    }

    @Test
    @DisplayName("a quote plus a trailing backslash run is refused rather than guessed at")
    void refusesTheUnverifiedCombination() {
        // Such a run becomes "immediately before a quote" once the JVM appends its own closing
        // quote, so it would need doubling -- but that has never been measured against a real
        // child, and every escaping rule in this class comes from measurement. Refusing beats
        // emitting something unverified. Unreachable from the only caller, whose values are JSON.
        assertThatThrownBy(() -> WindowsArgv.escapeForWindowsChild("\"a\"\\\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unverified");
    }

    @Test
    @DisplayName("encode is a no-op off Windows, where argv is passed as a real array")
    void encodeIsPlatformAware() {
        String schema = "{\"type\":\"object\"}";

        String encoded = WindowsArgv.encode(schema);

        if (WindowsArgv.isWindows()) {
            assertThat(encoded).isEqualTo("{\\\"type\\\":\\\"object\\\"}");
        } else {
            assertThat(encoded).isEqualTo(schema);
        }
    }
}
