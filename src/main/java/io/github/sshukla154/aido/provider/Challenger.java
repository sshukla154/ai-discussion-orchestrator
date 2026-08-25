package io.github.sshukla154.aido.provider;

import io.github.sshukla154.aido.provider.groq.ChallengerOutcome;

/**
 * Produces the challenger's turn, however it is obtained.
 *
 * <p>Introduced now rather than earlier on purpose. An interface written before a second
 * implementation exists fixes the wrong shape -- the originally specified synchronous
 * {@code generate} could not have survived a restart, and that was only obvious once a real
 * provider had been built. There are two implementations now: the API-backed one, and the test
 * stub that made a silent data-loss bug visible.
 *
 * <p>The manual path is deliberately <em>not</em> an implementation of this. A person pasting into
 * a chat cannot answer within a method call, and pretending otherwise is what the durable-ticket
 * design exists to avoid. Absence of an automatic answer is reported as an outcome instead.
 */
@FunctionalInterface
public interface Challenger {

    /**
     * @param prompt     the challenger prompt, with no embedded schema
     * @param jsonSchema the turn schema, passed to the provider rather than pasted into the prompt
     */
    ChallengerOutcome challenge(String prompt, String jsonSchema);
}
