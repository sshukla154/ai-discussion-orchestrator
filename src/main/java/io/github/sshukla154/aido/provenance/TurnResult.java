package io.github.sshukla154.aido.provenance;

import io.github.sshukla154.aido.provider.claude.CliInvocation;
import io.github.sshukla154.aido.provider.claude.CliResult;
import io.github.sshukla154.aido.provider.claude.ProcessOutcome;

/**
 * What came back, recorded after the provider call.
 *
 * <p>Nullable reference types rather than {@code Optional}: this is an on-disk shape, and a
 * recorded-but-absent value carries information an omitted key does not. {@code Optional} is an
 * API idiom and is unwrapped at this boundary.
 *
 * @param rawStdout the exact bytes received, never truncated. {@code CliResultParser} caps its own
 *                  excerpts for diagnostics, and inheriting that limit here would silently defeat
 *                  the whole point of the record. This is the trap to avoid in this class.
 */
public record TurnResult(
        String outcome,
        Integer exitCode,
        String rawStdout,
        String rawStderr,
        boolean stdoutComplete,
        boolean stderrComplete,
        boolean promptDelivered,
        Long wallMillis,
        Long pid,
        String processStart,
        String resolvedModel,
        String stopReason,
        Integer httpStatus,
        TokenUsage usage,
        String failureReason) {

    /**
     * Projects an invocation into the recorded shape.
     *
     * <p>Reads the raw stream content from the {@link ProcessOutcome} rather than the
     * {@link CliResult}, because classification discards it -- which is the reason
     * {@link CliInvocation} carries both.
     */
    public static TurnResult from(CliInvocation invocation) {
        ProcessOutcome outcome = invocation.outcome();
        CliResult result = invocation.result();

        String resolvedModel = null;
        String stopReason = null;
        Integer httpStatus = null;
        String failureReason = null;
        TokenUsage usage = null;

        switch (result) {
            case CliResult.Success s -> {
                resolvedModel = s.resolvedModel().orElse(null);
                stopReason = s.stopReason();
                usage = TokenUsage.from(s.usage());
            }
            case CliResult.Truncated t -> {
                resolvedModel = t.resolvedModel().orElse(null);
                usage = TokenUsage.from(t.usage());
                failureReason = "the reply hit the output ceiling and is a fragment, not a position";
            }
            case CliResult.RateLimited r -> {
                httpStatus = r.httpStatus().orElse(null);
                failureReason = r.message();
            }
            case CliResult.ApiError e -> {
                httpStatus = e.httpStatus().orElse(null);
                failureReason = e.message();
            }
            case CliResult.PreflightError e -> failureReason = e.kind() + ": " + e.stderr();
            case CliResult.SpawnFailed f -> failureReason = f.message();
            case CliResult.Timeout t -> failureReason = "timed out after " + t.wallMillis() + " ms";
            case CliResult.Unparseable u -> failureReason = u.reason();
        }

        return new TurnResult(
                result.getClass().getSimpleName(),
                outcome.status() == ProcessOutcome.Status.EXITED ? outcome.exitCode() : null,
                outcome.stdout(),
                outcome.stderr(),
                outcome.stdoutComplete(),
                outcome.stderrComplete(),
                outcome.promptDelivered(),
                outcome.wallMillis(),
                outcome.pid().orElse(null),
                outcome.processStart().map(Object::toString).orElse(null),
                resolvedModel,
                stopReason,
                httpStatus,
                usage,
                failureReason);
    }
}
