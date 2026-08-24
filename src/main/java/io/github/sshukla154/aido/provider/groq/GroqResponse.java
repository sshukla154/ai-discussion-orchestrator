package io.github.sshukla154.aido.provider.groq;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the provider returned, before interpretation.
 *
 * <p>Exists for the same reason {@code ProcessOutcome} does on the Claude side: it makes
 * interpretation a pure function over data, so every interesting response shape -- a 429 with and
 * without {@code retry-after}, a reply cut off at the completion cap, an error body echoing a
 * credential -- is reachable in a test from a recorded literal.
 *
 * <p>The alternative was a local HTTP server in the test, which is closer to the real thing and
 * genuinely better where it works. It does not work everywhere: {@code HttpServer.create} needs an
 * internal loopback socket pair for its selector, and that is blocked in some sandboxes, so those
 * tests failed on the development machine while a plain {@code ServerSocket} bound fine. A suite
 * that cannot run locally is a suite that gets ignored.
 *
 * @param headers lower-cased keys. HTTP header names are case-insensitive and providers are
 *                inconsistent about {@code Retry-After} versus {@code retry-after}; normalising
 *                once here is cheaper than every lookup having to remember.
 */
record GroqResponse(int status, Map<String, String> headers, String body) {

    GroqResponse {
        Map<String, String> lowered = new LinkedHashMap<>();
        headers.forEach((k, v) -> lowered.put(k.toLowerCase(Locale.ROOT), v));
        headers = Map.copyOf(lowered);
        body = body == null ? "" : body;
    }

    static GroqResponse of(HttpResponse<String> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });
        return new GroqResponse(response.statusCode(), headers, response.body());
    }

    Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }
}
