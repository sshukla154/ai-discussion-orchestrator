package io.github.sshukla154.aido.provider.groq;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the challenger turn against Groq's OpenAI-compatible chat completions endpoint.
 *
 * <p>Exists so the debate's central claim -- that an <em>independent</em> model finds what a
 * self-steelman cannot -- can be tested without a person pasting text between two browser tabs.
 * The manual path remains, and is what runs when no key is configured.
 *
 * <p>Uses the JDK's own HTTP client rather than a Spring one. This application deliberately has no
 * web starter, so {@code RestClient} is not on the classpath, and adding a web dependency to make
 * one outbound call would undo that decision for no benefit.
 *
 * <p>Three properties of the free tier shape this class:
 *
 * <ol>
 *   <li><b>8,000 tokens per minute, counting input and output together.</b> A substantive turn can
 *       consume most of that in one request, so the schema is sent as a request parameter rather
 *       than embedded in the prompt -- worth roughly 1,300 tokens of output headroom -- and the
 *       completion is explicitly capped so a long reply fails fast rather than being truncated
 *       mid-argument by the provider.
 *   <li><b>Rate limiting is an ordinary event, not an exception.</b> It gets its own outcome and
 *       the provider's own {@code retry-after} is preferred over any local guess.
 *   <li><b>Structured output is supported in strict mode</b> for this model, which the debate turn
 *       schema already satisfies: every property required, {@code additionalProperties: false}.
 * </ol>
 *
 * <p>The API key never enters a field, a log line, a prompt or a run record. It is read from the
 * environment at the moment the request is built and dropped immediately after.
 */
@Component
public final class GroqChallengerProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqChallengerProvider.class);

    private static final URI ENDPOINT = URI.create("https://api.groq.com/openai/v1/chat/completions");

    /**
     * Open-weight, free on this tier, and contractually excluded from training on submitted
     * content -- which matters because a debate prompt carries the user's real architecture.
     */
    private static final String MODEL = "openai/gpt-oss-120b";

    /**
     * Deliberately below what the per-minute allowance would permit outright.
     *
     * <p>The budget is shared with the input, and a request that asks for more than the remaining
     * allowance is rejected rather than served, so the cap has to leave room for a prompt whose
     * size varies with the argument being challenged.
     */
    private static final int MAX_COMPLETION_TOKENS = 4_500;

    private static final int EXCERPT_LIMIT = 2_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    private static final int HTTP_OK = 200;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /**
     * Built on first use, never in the constructor.
     *
     * <p>Constructing an {@code HttpClient} starts a selector, which needs an internal loopback
     * socket pair, and that is not available in every environment. Doing it eagerly made this
     * component impossible to instantiate there -- which took the whole Spring context down with
     * it, so an unrelated persistence test started failing. A bean that performs no I/O setup until
     * it is asked to do I/O is the right shape anyway.
     */
    private volatile HttpClient http;

    private HttpClient http() {
        HttpClient existing = http;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (http == null) {
                http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            }
            return http;
        }
    }

    /**
     * @param prompt     the challenger prompt with <b>no embedded schema</b>; it travels as a
     *                   request parameter instead
     * @param jsonSchema the debate turn schema, sent in strict mode
     */
    public ChallengerOutcome challenge(String prompt, String jsonSchema) {
        return GroqApiKey.fromEnvironment()
                .map(key -> send(prompt, jsonSchema, key, ENDPOINT))
                .orElseGet(() -> new ChallengerOutcome.Unavailable(
                        "no " + GroqApiKey.ENV_VARIABLE + " in the environment, so the challenger "
                                + "turn is prepared for a person to run manually"));
    }

    ChallengerOutcome send(String prompt, String jsonSchema, String apiKey, URI endpoint) {
        long start = System.nanoTime();
        String body;
        try {
            body = requestBody(prompt, jsonSchema);
        } catch (JacksonException e) {
            return new ChallengerOutcome.Malformed(
                    "the turn schema is not valid JSON: " + e.getOriginalMessage(), "", 0L);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                // The one place the key is used. Not stored, not logged, not recorded.
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(GroqResponse.of(response), elapsedMillis(start));
        } catch (IOException e) {
            // Message only. An exception from the HTTP client can carry the request in its cause
            // chain, and the request carries the Authorization header.
            return new ChallengerOutcome.TransportFailure(
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsedMillis(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChallengerOutcome.TransportFailure("interrupted", elapsedMillis(start));
        } catch (UncheckedIOException e) {
            // Thrown while building the client, not while sending: an environment with no usable
            // network stack. Reported as a transport failure rather than escaping, so a debate
            // degrades to the manual challenger instead of aborting.
            return new ChallengerOutcome.TransportFailure(
                    "no usable HTTP client in this environment: " + e.getMessage(), elapsedMillis(start));
        }
    }

    /** Package-private so the request shape can be asserted without a live endpoint. */
    String requestBody(String prompt, String jsonSchema) {
        ObjectNode schemaSpec = mapper.createObjectNode();
        schemaSpec.put("name", "debate_turn");
        // Strict mode. The turn schema already meets its requirements, and best-effort mode would
        // let a reply omit a field the orchestrator branches on.
        schemaSpec.put("strict", true);
        schemaSpec.set("schema", mapper.readTree(jsonSchema));

        ObjectNode responseFormat = mapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.set("json_schema", schemaSpec);

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);

        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        // Not zero. A challenger whose job is to find objections benefits from some variation, and
        // zero temperature on a critique task tends to produce the same three points every time.
        root.put("temperature", 0.6);
        root.set("response_format", responseFormat);
        root.putArray("messages").add(message);
        return mapper.writeValueAsString(root);
    }

    /** Pure, so every response shape is reachable from a recorded literal in a test. */
    ChallengerOutcome interpret(GroqResponse response, long wallMillis) {
        int status = response.status();
        String body = response.body();

        if (status == HTTP_TOO_MANY_REQUESTS) {
            Optional<Duration> retryAfter = response.header("retry-after")
                    .flatMap(GroqChallengerProvider::parseSeconds);
            log.warn("challenger rate limited, retry-after={}",
                    retryAfter.map(d -> d.toSeconds() + "s").orElse("unspecified"));
            return new ChallengerOutcome.RateLimited(retryAfter, errorMessage(body), wallMillis);
        }
        if (status != HTTP_OK) {
            log.warn("challenger rejected the request with status {}", status);
            return new ChallengerOutcome.Rejected(status, errorMessage(body), wallMillis);
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            return new ChallengerOutcome.Malformed(
                    "response body is not JSON: " + e.getOriginalMessage(), excerpt(body), wallMillis);
        }

        JsonNode choice = root.path("choices").path(0);
        String finishReason = choice.path("finish_reason").asString("");
        if ("length".equals(finishReason)) {
            // The provider stopped mid-argument. A fragment is not a challenge, and accepting one
            // would let half an objection become the record of what the challenger thinks.
            return new ChallengerOutcome.Malformed(
                    "the reply hit the completion cap of " + MAX_COMPLETION_TOKENS
                            + " tokens and is a fragment, not a challenge",
                    excerpt(choice.path("message").path("content").asString("")), wallMillis);
        }

        String content = choice.path("message").path("content").asString("");
        if (content.isBlank()) {
            return new ChallengerOutcome.Malformed(
                    "the reply carried no content (finish_reason=" + finishReason + ")",
                    excerpt(body), wallMillis);
        }

        Map<String, Object> structured;
        try {
            JsonNode parsed = mapper.readTree(content);
            if (!parsed.isObject()) {
                return new ChallengerOutcome.Malformed(
                        "the reply is valid JSON but not an object", excerpt(content), wallMillis);
            }
            structured = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : parsed.properties()) {
                structured.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class));
            }
        } catch (JacksonException e) {
            // Strict mode should prevent this. If it happens the schema and the provider have
            // diverged, which is worth surfacing rather than repairing.
            return new ChallengerOutcome.Malformed(
                    "the reply is not valid JSON despite strict mode: " + e.getOriginalMessage(),
                    excerpt(content), wallMillis);
        }

        ChallengerOutcome.TokenUsage usage = new ChallengerOutcome.TokenUsage(
                root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(0),
                response.header("x-ratelimit-remaining-tokens")
                        .flatMap(GroqChallengerProvider::parseInt));

        log.info("challenger ok model={} in={} out={} remainingTokens={} wallMs={}",
                root.path("model").asString(MODEL), usage.promptTokens(), usage.completionTokens(),
                usage.remainingTokens().map(String::valueOf).orElse("unreported"), wallMillis);
        return new ChallengerOutcome.Success(structured, usage, wallMillis);
    }

    /**
     * Pulls a human-readable reason out of an error body without ever echoing a credential.
     *
     * <p>A provider error body can quote the request that caused it, so the extracted text is
     * screened. This should never trigger, which is exactly why it is here.
     */
    private String errorMessage(String body) {
        String message = body;
        try {
            JsonNode node = mapper.readTree(body).path("error").path("message");
            if (node.isString()) {
                message = node.asString();
            }
        } catch (JacksonException e) {
            log.debug("error body was not JSON, using the raw excerpt");
        }
        if (GroqApiKey.looksLikeAKey(message)) {
            return "[withheld: the provider echoed something that looks like a credential]";
        }
        return excerpt(message);
    }

    private static Optional<Duration> parseSeconds(String value) {
        return parseInt(value).map(Duration::ofSeconds);
    }

    private static Optional<Integer> parseInt(String value) {
        try {
            // Groq documents retry-after in seconds, but it can arrive fractional. Rounding up
            // keeps a wait from being one poll too early.
            return Optional.of((int) Math.ceil(Double.parseDouble(value.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT) + "…[truncated]";
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
