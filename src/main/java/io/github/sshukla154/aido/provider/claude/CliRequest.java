package io.github.sshukla154.aido.provider.claude;

import java.time.Duration;
import java.util.Optional;

/**
 * One non-interactive Claude CLI invocation.
 *
 * <p>The prompt is carried here but is never placed on the command line — it goes to the
 * child's stdin. Debate prompts contain quotes, newlines, code fences and percent signs,
 * and Java builds a single Windows command line with its own quoting rules, so stdin
 * removes an entire class of platform-specific escaping bugs.
 *
 * <p>{@code jsonSchema} has no such escape hatch: the CLI rejects a file path
 * ("--json-schema is not valid JSON"), so it must be passed inline as an argument. That
 * makes argv round-tripping of quote-heavy JSON a tested requirement, not an assumption.
 *
 * @param sessionId  session to create ({@code --session-id}) or resume ({@code --resume});
 *                   a given id may only ever be *created* once
 */
public record CliRequest(
        String prompt,
        Optional<String> model,
        Optional<String> effort,
        Optional<String> jsonSchema,
        Optional<String> sessionId,
        SessionMode sessionMode,
        Duration timeout) {

    public enum SessionMode {
        /** No session flag at all; the CLI allocates and returns one. */
        STATELESS,
        /** {@code --session-id <uuid>} — single use per uuid, forever. */
        CREATE,
        /** {@code --resume <uuid>} — every turn after the first. */
        RESUME
    }

    public static CliRequest stateless(String prompt, Duration timeout) {
        return new CliRequest(prompt, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), SessionMode.STATELESS, timeout);
    }

    public CliRequest withSchema(String schema) {
        return new CliRequest(prompt, model, effort, Optional.of(schema), sessionId, sessionMode, timeout);
    }

    public CliRequest withModel(String m) {
        return new CliRequest(prompt, Optional.of(m), effort, jsonSchema, sessionId, sessionMode, timeout);
    }

    public CliRequest withEffort(String e) {
        return new CliRequest(prompt, model, Optional.of(e), jsonSchema, sessionId, sessionMode, timeout);
    }

    public CliRequest resuming(String id) {
        return new CliRequest(prompt, model, effort, jsonSchema, Optional.of(id), SessionMode.RESUME, timeout);
    }

    public CliRequest creating(String id) {
        return new CliRequest(prompt, model, effort, jsonSchema, Optional.of(id), SessionMode.CREATE, timeout);
    }
}
