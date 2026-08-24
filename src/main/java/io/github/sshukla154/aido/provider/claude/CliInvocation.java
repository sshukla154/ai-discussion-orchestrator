package io.github.sshukla154.aido.provider.claude;

import java.util.List;

/**
 * One CLI invocation: both the classified outcome and the raw material it was derived from.
 *
 * <p>Exists because classification is lossy by design. {@link CliResultParser} reduces a
 * {@link ProcessOutcome} to a {@link CliResult}, and the reduction throws away the exact bytes
 * received -- {@code Success} and {@code Truncated} carry no stdout at all, and
 * {@code Unparseable} keeps only a capped excerpt. That is right for the state machine, which
 * should branch on meaning rather than on text, and wrong for the provenance record, whose entire
 * job is to answer "what actually came back" months later.
 *
 * <p>Rather than a second recording-aware entry point on the client -- where the
 * provenance-correct call would be the unusual one, and would eventually stop being made -- every
 * invocation returns both and the caller takes what it needs.
 *
 * @param args the argument tail actually passed, excluding the executable path, which is
 *             machine-identifying and must not reach a tracked file
 */
public record CliInvocation(CliResult result, ProcessOutcome outcome, List<String> args) {

    public CliInvocation {
        args = List.copyOf(args);
    }
}
