package io.github.sshukla154.aido.debate;

/**
 * Something a participant now accepts that they previously did not, and the argument that moved
 * them. This is how "the debate changed someone's mind" becomes machine-readable rather than
 * something a human has to infer by reading two rounds of prose side by side.
 */
public record Concession(String stableKey, String nowAccepted, String persuadedBy) {
}
