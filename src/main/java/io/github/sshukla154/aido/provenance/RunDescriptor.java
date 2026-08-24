package io.github.sshukla154.aido.provenance;

/**
 * The facts that are fixed when a run opens: what is being argued about, and by what.
 *
 * <p>Deliberately records no absolute path to the executable. It is machine-identifying, and once
 * the version is known it adds nothing that would help diagnose a strange conclusion.
 */
public record RunDescriptor(
        String question,
        String objective,
        String constraints,
        String architectModel,
        String architectEffort,
        String challengerDescription,
        String appVersion,
        String javaVersion) {
}
