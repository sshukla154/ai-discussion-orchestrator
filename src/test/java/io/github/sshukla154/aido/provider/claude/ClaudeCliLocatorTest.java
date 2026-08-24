package io.github.sshukla154.aido.provider.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The locator decides whether the application can find the CLI at all, and it was previously
 * untested. Only the system-property override is exercised here: the environment-variable path
 * and the PATH scan cannot be driven from a test without mutating the JVM environment, which is
 * not worth the reflection hackery.
 */
class ClaudeCliLocatorTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearOverride() {
        System.clearProperty(ClaudeCliLocator.OVERRIDE_PROPERTY);
    }

    @Test
    @DisplayName("an override pointing at a real executable wins")
    void overrideTakesPrecedence() throws Exception {
        Path exe = executableFile("claude-stub");
        System.setProperty(ClaudeCliLocator.OVERRIDE_PROPERTY, exe.toString());

        assertThat(ClaudeCliLocator.locate()).contains(exe.toAbsolutePath());
    }

    @Test
    @DisplayName("surrounding whitespace in the override is tolerated")
    void overrideIsTrimmed() throws Exception {
        Path exe = executableFile("claude-stub");
        System.setProperty(ClaudeCliLocator.OVERRIDE_PROPERTY, "  " + exe + "  ");

        assertThat(ClaudeCliLocator.locate()).contains(exe.toAbsolutePath());
    }

    @Test
    @DisplayName("an override pointing at a directory is ignored rather than returned")
    void overridePointingAtADirectoryIsRejected() {
        // Returning a directory here would surface much later as an opaque spawn failure.
        System.setProperty(ClaudeCliLocator.OVERRIDE_PROPERTY, dir.toString());

        // Falls through to the PATH scan, which may or may not find a real CLI on this machine,
        // so the assertion is only that the directory itself is never the answer.
        assertThat(ClaudeCliLocator.locate()).isNotEqualTo(Optional.of(dir.toAbsolutePath()));
    }

    @Test
    @DisplayName("an override pointing at nothing falls through instead of failing")
    void missingOverrideFileFallsThrough() {
        System.setProperty(ClaudeCliLocator.OVERRIDE_PROPERTY,
                dir.resolve("does-not-exist").toString());

        assertThat(ClaudeCliLocator.locate())
                .isNotEqualTo(Optional.of(dir.resolve("does-not-exist").toAbsolutePath()));
    }

    @Test
    @DisplayName("require names the override property, so the fix is in the message")
    void requireExplainsHowToFixIt() {
        System.setProperty(ClaudeCliLocator.OVERRIDE_PROPERTY,
                dir.resolve("nope").toString());

        // On a machine with a real CLI on PATH the fallback succeeds and there is nothing to
        // assert. Skipping says so out loud; an `if` with no `else` would report green having
        // verified nothing, which is indistinguishable from a passing check.
        Assumptions.assumeTrue(ClaudeCliLocator.locate().isEmpty(),
                "a real CLI is installed, so require() cannot be made to fail here");

        assertThatThrownBy(ClaudeCliLocator::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ClaudeCliLocator.OVERRIDE_PROPERTY)
                .hasMessageContaining(ClaudeCliLocator.OVERRIDE_ENV);
    }

    private Path executableFile(String name) throws Exception {
        // Windows treats any existing regular file as executable, so no permission juggling is
        // needed there; on other platforms the bit has to be set explicitly.
        Path exe = dir.resolve(WindowsArgv.isWindows() ? name + ".exe" : name);
        Files.writeString(exe, "#!/bin/sh\nexit 0\n");
        exe.toFile().setExecutable(true);
        return exe;
    }
}
