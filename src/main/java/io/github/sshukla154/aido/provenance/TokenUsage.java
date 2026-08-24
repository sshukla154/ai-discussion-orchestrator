package io.github.sshukla154.aido.provenance;

import io.github.sshukla154.aido.provider.claude.CliResult;

/**
 * Token counts for one turn. Recorded per turn because "what did this debate cost" is the number
 * that decides whether the tool is worth using, and it can only be summed if it was captured.
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens) {

    static TokenUsage from(CliResult.Usage usage) {
        return new TokenUsage(usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens(), usage.cacheReadInputTokens());
    }
}
