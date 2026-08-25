package io.github.sshukla154.aido.provenance;

import io.github.sshukla154.aido.provider.claude.CliInvocation;
import io.github.sshukla154.aido.provider.groq.ChallengerOutcome;
import io.github.sshukla154.aido.provider.groq.GroqChallengerProvider;
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
    /**
     * Projects a challenger outcome onto the same record the CLI turns use.
     *
     * <p>One shape for both providers, so a run directory reads the same whichever answered. The
     * fields a process has and an HTTP call does not -- exit code, pid, stream completeness -- are
     * null rather than invented, because a zero exit code for something that never was a process
     * would be a fabricated fact in a record whose whole purpose is not fabricating facts.
     */
    public static TurnResult from(ChallengerOutcome outcome) {
        return switch (outcome) {
            case ChallengerOutcome.Success s -> new TurnResult(
                    "Success", null, s.rawBody(), "", true, true, true, s.wallMillis(),
                    null, null, GroqChallengerProvider.MODEL, "stop", 200,
                    new TokenUsage(s.usage().promptTokens(), s.usage().completionTokens(), 0, 0), null);
            case ChallengerOutcome.RateLimited r -> new TurnResult(
                    "RateLimited", null, "", "", true, true, true, r.wallMillis(),
                    null, null, GroqChallengerProvider.MODEL, null, 429, null,
                    r.message() + r.retryAfter().map(d -> " (retry after " + d.toSeconds() + "s)").orElse(""));
            case ChallengerOutcome.Rejected r -> new TurnResult(
                    "Rejected", null, "", "", true, true, true, r.wallMillis(),
                    null, null, GroqChallengerProvider.MODEL, null, r.httpStatus(), null, r.message());
            case ChallengerOutcome.Malformed m -> new TurnResult(
                    "Malformed", null, m.rawExcerpt(), "", true, true, true, m.wallMillis(),
                    null, null, GroqChallengerProvider.MODEL, null, null, null, m.reason());
            case ChallengerOutcome.TransportFailure f -> new TurnResult(
                    "TransportFailure", null, "", "", false, false, false, f.wallMillis(),
                    null, null, GroqChallengerProvider.MODEL, null, null, null, f.reason());
            case ChallengerOutcome.Unavailable u -> new TurnResult(
                    "Unavailable", null, "", "", false, false, false, 0L,
                    null, null, null, null, null, null, u.reason());
        };
    }

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
