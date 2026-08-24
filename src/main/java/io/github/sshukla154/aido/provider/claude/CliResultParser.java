package io.github.sshukla154.aido.provider.claude;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a {@link ProcessOutcome} into a {@link CliResult}.
 *
 * <p>A pure function with no I/O, because every interesting failure mode of the CLI is reachable
 * here from a recorded literal. The order of the checks below is load-bearing and must not be
 * rearranged.
 */
@Component
public class CliResultParser {

    private static final int EXCERPT_LIMIT = 4_000;

    /** The CLI reports this when a reply hit the output-token ceiling mid-answer. */
    private static final String STOP_REASON_TRUNCATED = "max_tokens";

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public CliResult parse(ProcessOutcome outcome) {
        switch (outcome.status()) {
            case SPAWN_FAILED -> {
                return new CliResult.SpawnFailed(outcome.failureMessage(), outcome.wallMillis());
            }
            case TIMED_OUT -> {
                return new CliResult.Timeout(
                        outcome.wallMillis(), outcome.pid(), outcome.processStart());
            }
            case INTERRUPTED -> {
                return new CliResult.Unparseable(outcome.exitCode(), "", "",
                        outcome.failureMessage(), outcome.wallMillis());
            }
            case EXITED -> {
                // fall through to the classification below
            }
        }

        // A truncated drain also produces blank stdout, which would otherwise be read as a
        // pre-flight failure -- the one classification that means "the prompt never landed, a
        // retry is safe". Getting that wrong on a turn that may have completed is how a debate
        // acquires a duplicate turn, so an incomplete capture is never classified.
        if (!outcome.stdoutComplete()) {
            return new CliResult.Unparseable(outcome.exitCode(), excerpt(outcome.stdout()),
                    excerpt(outcome.stderr()),
                    "stdout could not be captured completely, so the outcome is unknown; "
                            + "the turn may have completed",
                    outcome.wallMillis());
        }

        // Pre-flight validation failures emit no JSON whatsoever, so this must come before any
        // attempt to parse stdout.
        boolean stdoutBlank = outcome.stdout() == null || outcome.stdout().isBlank();
        boolean stderrPresent = outcome.stderr() != null && !outcome.stderr().isBlank();
        if (stdoutBlank && stderrPresent) {
            return new CliResult.PreflightError(
                    classifyPreflight(outcome.stderr()), outcome.stderr().strip(), outcome.wallMillis());
        }

        if (stdoutBlank) {
            return new CliResult.Unparseable(outcome.exitCode(), "", excerpt(outcome.stderr()),
                    "exit=" + outcome.exitCode() + " with empty stdout and empty stderr",
                    outcome.wallMillis());
        }

        JsonNode root;
        try {
            root = mapper.readTree(outcome.stdout());
        } catch (JacksonException e) {
            return new CliResult.Unparseable(outcome.exitCode(), excerpt(outcome.stdout()),
                    excerpt(outcome.stderr()), "stdout is not valid JSON: " + e.getOriginalMessage(),
                    outcome.wallMillis());
        }
        if (!root.isObject()) {
            return new CliResult.Unparseable(outcome.exitCode(), excerpt(outcome.stdout()),
                    excerpt(outcome.stderr()), "stdout JSON is not an object", outcome.wallMillis());
        }

        // Never branch on "subtype": the CLI reports subtype="success" alongside is_error=true,
        // so it is not a success signal.
        if (root.path("is_error").asBoolean(false)) {
            return classifyError(root, outcome);
        }

        String stopReason = root.path("stop_reason").asString("");
        CliResult.Usage usage = readUsage(root.path("usage"));

        if (STOP_REASON_TRUNCATED.equals(stopReason)) {
            return new CliResult.Truncated(
                    root.path("result").asString(""),
                    root.path("session_id").asString(""),
                    resolvePrimaryModel(root),
                    usage,
                    outcome.wallMillis());
        }

        return new CliResult.Success(
                root.path("result").asString(""),
                readStructuredOutput(root),
                root.path("session_id").asString(""),
                resolvePrimaryModel(root),
                stopReason,
                usage,
                root.path("total_cost_usd").asDouble(0d),
                outcome.wallMillis());
    }

    private CliResult classifyError(JsonNode root, ProcessOutcome outcome) {
        JsonNode statusNode = root.get("api_error_status");
        Optional<Integer> status = statusNode == null || statusNode.isNull()
                ? Optional.empty()
                : Optional.of(statusNode.asInt());
        String message = root.path("result").asString("");
        String sessionId = root.path("session_id").asString("");

        if (status.filter(s -> s == HTTP_TOO_MANY_REQUESTS).isPresent()) {
            return new CliResult.RateLimited(message, status, sessionId, outcome.wallMillis());
        }
        return new CliResult.ApiError(message, status, sessionId,
                outcome.stderr() == null ? "" : outcome.stderr().strip(), outcome.wallMillis());
    }

    /**
     * Identifies the model that served the turn, or reports that it could not be determined.
     *
     * <p>{@code modelUsage} is not single-entry: a lightweight auxiliary model appears alongside
     * the real one for side work such as session-title generation, and there is no top-level
     * {@code model} field. The primary is the entry whose output-token count matches the
     * top-level {@code usage} block.
     *
     * <p>When nothing matches, this returns empty rather than guessing. An earlier version fell
     * back to "largest producer wins", which is biased exactly the wrong way for this project:
     * a schema-constrained reply is a handful of tokens, so an ordinary auxiliary call can easily
     * emit more. Since this value is provenance -- which model said this -- a wrong answer
     * dressed as a right one is worse than an absent one.
     */
    private Optional<String> resolvePrimaryModel(JsonNode root) {
        JsonNode modelUsage = root.path("modelUsage");
        if (!modelUsage.isObject() || modelUsage.isEmpty()) {
            return Optional.empty();
        }
        if (modelUsage.size() == 1) {
            return Optional.of(modelUsage.propertyNames().iterator().next());
        }

        JsonNode topOutput = root.path("usage").get("output_tokens");
        if (topOutput == null || topOutput.isNull()) {
            return Optional.empty();
        }
        long target = topOutput.asLong();

        String match = null;
        for (Map.Entry<String, JsonNode> e : modelUsage.properties()) {
            if (e.getValue().path("outputTokens").asLong(Long.MIN_VALUE) == target) {
                if (match != null) {
                    // Two models reporting the same output count. Picking either would be a
                    // coin toss dressed as a determination.
                    return Optional.empty();
                }
                match = e.getKey();
            }
        }
        return Optional.ofNullable(match);
    }

    /** Present only when {@code --json-schema} was supplied; already validated by the CLI. */
    private Optional<Map<String, Object>> readStructuredOutput(JsonNode root) {
        JsonNode node = root.get("structured_output");
        if (node == null || node.isNull() || !node.isObject()) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            out.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class));
        }
        return Optional.of(out);
    }

    private CliResult.Usage readUsage(JsonNode usage) {
        return new CliResult.Usage(
                usage.path("input_tokens").asLong(0L),
                usage.path("output_tokens").asLong(0L),
                usage.path("cache_creation_input_tokens").asLong(0L),
                usage.path("cache_read_input_tokens").asLong(0L));
    }

    /**
     * Best-effort classification of a pre-flight failure.
     *
     * <p>String matching on human-readable stderr is brittle by nature, so the raw text is always
     * retained on the result and {@code OTHER} is a first-class outcome rather than a parse
     * failure. The enum is a convenience for the state machine; the stderr text is the record.
     */
    private CliResult.PreflightError.Kind classifyPreflight(String stderr) {
        String s = stderr.toLowerCase(Locale.ROOT);
        if (s.contains("no conversation found with session id")) {
            return CliResult.PreflightError.Kind.UNKNOWN_SESSION;
        }
        if (s.contains("is already in use")) {
            return CliResult.PreflightError.Kind.SESSION_ID_ALREADY_IN_USE;
        }
        if (s.contains("invalid session id")) {
            return CliResult.PreflightError.Kind.INVALID_SESSION_ID;
        }
        if (s.contains("json-schema is not valid json")) {
            return CliResult.PreflightError.Kind.INVALID_JSON_SCHEMA;
        }
        if (s.contains("not logged in")) {
            return CliResult.PreflightError.Kind.NOT_LOGGED_IN;
        }
        return CliResult.PreflightError.Kind.OTHER;
    }

    private String excerpt(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= EXCERPT_LIMIT ? s : s.substring(0, EXCERPT_LIMIT) + "…[truncated]";
    }
}
