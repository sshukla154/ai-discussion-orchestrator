package io.github.sshukla154.aido.provenance;

import java.util.List;

/**
 * What was about to be sent, recorded before the provider is called.
 *
 * <p>This is the artifact correction C6 exists for. Context construction is itself a decision:
 * dropping a constraint from the assembled prompt silently changes the outcome, and without the
 * exact text on disk there is no way afterwards to tell a wrong model from a wrong prompt.
 *
 * @param sequence      the orchestrator's turn number. Passed in rather than counted here, which
 *                      removes shared mutable state and with it the concurrency question.
 * @param promptText    the exact text sent on stdin, never truncated
 * @param templateId    which template produced it
 * @param templateSha256 hash of the unrendered template, identifying the builder version
 * @param schemaSha256  hash of the schema the model was held to, taken before argv escaping,
 *                      since escaping is transport rather than content
 * @param cliArgs       the argument tail, with the schema value replaced by its hash so the flags
 *                      stay readable and the executable path never reaches disk
 */
public record TurnRequest(
        int sequence,
        String participant,
        String role,
        String promptText,
        String templateId,
        String templateSha256,
        String templateText,
        String schemaVersion,
        String schemaSha256,
        String schemaText,
        String model,
        String effort,
        String sessionId,
        String sessionMode,
        long timeoutMillis,
        List<String> cliArgs) {

    public TurnRequest {
        cliArgs = List.copyOf(cliArgs);
    }
}
