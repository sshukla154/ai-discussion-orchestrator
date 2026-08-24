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
 * <p>A pure function with no I/O, because every interesting failure mode of the CLI is
 * reachable here from a recorded fixture. The order of the checks below is load-bearing
 * and must not be rearranged.
 */
@Component
public class CliResultParser {

    private static final int EXCERPT_LIMIT = 4_000;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public CliResult parse(ProcessOutcome outcome) {
        if (outcome.timedOut()) {
            return new CliResult.Timeout(outcome.wallMillis(), outcome.pid());
        }

        // Pre-flight validation failures emit no JSON whatsoever, so this check must come
        // before any attempt to parse stdout.
        boolean stdoutBlank = outcome.stdout() == null || outcome.stdout().isBlank();
        boolean stderrPresent = outcome.stderr() != null && !outcome.stderr().isBlank();
        if (stdoutBlank && stderrPresent) {
            return new CliResult.PreflightError(
                    classifyPreflight(outcome.stderr()), outcome.stderr().strip(), outcome.wallMillis());
        }

        if (stdoutBlank) {
            return new CliResult.Unparseable(outcome.exitCode(), "", excerpt(outcome.stderr()),
                    "exit=" + outcome.exitCode() + " with empty stdout and empty stderr", outcome.wallMillis());
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

        // Never branch on "subtype": the CLI reports subtype="success" alongside
        // is_error=true, so it is not a success signal.
        if (root.path("is_error").asBoolean(false)) {
            JsonNode status = root.get("api_error_status");
            return new CliResult.ApiError(
                    root.path("result").asString(""),
                    status == null || status.isNull() ? Optional.empty() : Optional.of(status.asInt()),
                    root.path("session_id").asString(""),
                    outcome.stderr() == null ? "" : outcome.stderr().strip(),
                    outcome.wallMillis());
        }

        return new CliResult.Success(
                root.path("result").asString(""),
                readStructuredOutput(root),
                root.path("session_id").asString(""),
                resolvePrimaryModel(root),
                root.path("stop_reason").asString(""),
                readUsage(root.path("usage")),
                root.path("total_cost_usd").asDouble(0d),
                outcome.wallMillis());
    }

    /**
     * Picks the model that actually served the turn.
     *
     * <p>{@code modelUsage} is not single-entry: a lightweight auxiliary model appears
     * alongside the real one for side work such as session-title generation. The primary
     * is the entry whose output-token count matches the top-level {@code usage} block;
     * failing that, the largest producer wins.
     */
    private String resolvePrimaryModel(JsonNode root) {
        JsonNode modelUsage = root.path("modelUsage");
        if (!modelUsage.isObject() || modelUsage.isEmpty()) {
            return "";
        }
        long topOutput = root.path("usage").path("output_tokens").asLong(-1L);

        String best = "";
        long bestOutput = Long.MIN_VALUE;
        for (Map.Entry<String, JsonNode> e : modelUsage.properties()) {
            long out = e.getValue().path("outputTokens").asLong(0L);
            if (topOutput >= 0 && out == topOutput) {
                return e.getKey();
            }
            if (out > bestOutput) {
                bestOutput = out;
                best = e.getKey();
            }
        }
        return best;
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
     * <p>This is string matching on human-readable stderr, which is brittle by nature — so
     * the raw text is always retained on the result and {@code OTHER} is a first-class
     * outcome rather than a parse failure. The enum is a convenience for the state machine;
     * the stderr text is the record of truth.
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
