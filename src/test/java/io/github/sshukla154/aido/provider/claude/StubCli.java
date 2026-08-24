package io.github.sshukla154.aido.provider.claude;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A fake Claude CLI, spawned as a real child process by the JVM under test.
 *
 * <p>Using the JVM itself as the stub is what makes these tests worth having: they go through
 * the genuine {@link ProcessBuilder} argv-construction and pipe machinery, so they can catch
 * platform quoting bugs and pipe deadlocks that a mocked-out client never would. The real CLI
 * is a 300+ MB binary and costs money per call, so it is unsuitable for a unit suite.
 *
 * <p>The first argument selects a behaviour; everything after it is whatever the client under
 * test appended, and is available for round-trip assertions.
 */
public final class StubCli {

    private StubCli() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "success" : args[0];
        String[] rest = args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);

        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

        switch (mode) {
            case "success" -> {
                out.print(successEnvelope("PONG", null));
                System.exit(0);
            }
            case "schema" -> {
                out.print(successEnvelope("{\"verdict\":\"YES\"}",
                        "{\"verdict\":\"YES\",\"reason\":\"because\"}"));
                System.exit(0);
            }
            case "apierror" -> {
                // Class A: parseable envelope with is_error true, plus a stderr diagnostic.
                out.print("""
                        {"type":"result","subtype":"success","is_error":true,\
                        "terminal_reason":"api_error","api_error_status":404,\
                        "result":"There is an issue with the selected model.",\
                        "session_id":"11111111-2222-3333-4444-555555555555",\
                        "total_cost_usd":0,"modelUsage":{}}""");
                err.print("[claude-code:unrecognized_model] {\"model\":\"nope\"}");
                System.exit(1);
            }
            case "preflight-unknown-session" -> {
                // Class B: no JSON at all, --output-format json ignored.
                err.print("No conversation found with session ID: 99999999-8888-7777-6666-555555555555");
                System.exit(1);
            }
            case "preflight-bad-schema" -> {
                err.print("Error: --json-schema is not valid JSON: JSON Parse error: Unexpected identifier \"C\"");
                System.exit(1);
            }
            case "malformed-json" -> {
                out.print("{\"type\":\"result\", this is not json");
                System.exit(0);
            }
            case "empty" -> System.exit(1);
            case "hang" -> Thread.sleep(Long.MAX_VALUE);
            case "flood" -> {
                // Writes far more than any pipe buffer holds, to BOTH streams. A client that
                // does not drain concurrently deadlocks here instead of completing.
                String chunk = "x".repeat(8_192);
                for (int i = 0; i < 256; i++) {
                    err.print(chunk);
                }
                StringBuilder padding = new StringBuilder();
                for (int i = 0; i < 256; i++) {
                    padding.append(chunk);
                }
                out.print(successEnvelope(padding.toString(), null));
                System.exit(0);
            }
            case "echo-args" -> {
                // Round-trips the received argv so quoting can be asserted exactly.
                out.print(successEnvelope(jsonEscape(String.join("", rest)), null));
                System.exit(0);
            }
            case "echo-stdin" -> {
                out.print(successEnvelope(jsonEscape(readStdin()), null));
                System.exit(0);
            }
            default -> {
                err.print("stub: unknown mode " + mode);
                System.exit(2);
            }
        }
    }

    private static String readStdin() throws IOException {
        try (InputStream in = System.in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String successEnvelope(String result, String structuredOutput) {
        String structured = structuredOutput == null ? "" : ",\"structured_output\":" + structuredOutput;
        String stopReason = structuredOutput == null ? "end_turn" : "tool_use";
        return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"terminal_reason\":\"completed\","
                + "\"result\":\"" + result + "\","
                + "\"session_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
                + "\"stop_reason\":\"" + stopReason + "\","
                + "\"total_cost_usd\":0.0244,"
                + "\"usage\":{\"input_tokens\":2,\"output_tokens\":133,"
                + "\"cache_creation_input_tokens\":5528,\"cache_read_input_tokens\":0},"
                // Two entries on purpose: the real CLI reports an auxiliary model alongside
                // the one that served the turn, and only the latter must be reported.
                + "\"modelUsage\":{"
                + "\"claude-haiku-4-5-20251001\":{\"inputTokens\":902,\"outputTokens\":12},"
                + "\"claude-sonnet-5\":{\"inputTokens\":2,\"outputTokens\":133}"
                + "}"
                + structured
                + "}";
    }

    /**
     * Escapes every control character, not just the familiar ones.
     *
     * <p>An earlier version handled only tab, CR, LF and the two structural characters. A stray
     * 0x1F then reached the output unescaped, and the client correctly reported the envelope as
     * unparseable. The failure was in this stub rather than the code under test, which is a
     * good reason for a test double to generate strictly valid output.
     */
    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
