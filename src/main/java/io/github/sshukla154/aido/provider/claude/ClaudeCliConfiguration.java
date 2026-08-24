package io.github.sshukla154.aido.provider.claude;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Builds the CLI client, which cannot be a {@code @Component} because it needs a resolved
 * executable path and a pinned working directory.
 */
@Configuration
class ClaudeCliConfiguration {

    /**
     * One working directory, reused for every turn and kept empty.
     *
     * <p>Pinning it makes runs reproducible, since the CLI derives its transcript location from
     * its working directory. Keeping it empty means no project configuration is discovered, which
     * is both the cheap path and the safe one -- the debate loop feeds one model's output to
     * another, so the child is spawned with nothing it could be talked into using.
     */
    /**
     * Lazy, because {@link ClaudeCliLocator#require()} throws when no CLI is installed and that
     * must not stop the application from starting. Only a command that actually runs a turn needs
     * the executable; the test suite and every other path must boot without it.
     */
    @Bean
    @Lazy
    ClaudeCliClient claudeCliClient(CliResultParser parser) {
        Path executable = ClaudeCliLocator.require();
        Path cwd = Path.of(System.getProperty("user.home"), ".ai-discussion-orchestrator", "cli-cwd");
        try {
            Files.createDirectories(cwd);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the CLI working directory " + cwd, e);
        }
        return new ClaudeCliClient(List.of(executable.toString()), cwd, parser);
    }
}
