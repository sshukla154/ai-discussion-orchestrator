package io.github.sshukla154.aido.provider.claude;

import java.util.Locale;

/**
 * Makes a quote-bearing argument survive process creation on Windows.
 *
 * <p>Windows has no argv: a parent passes one command-line string and each child re-splits it.
 * The JVM only wraps an argument in quotes when it contains whitespace, and it does not escape
 * double quotes inside the value at all. So a JSON argument is corrupted twice over. Measured,
 * passing {@code {"type":"object","p":"C:\tmp 100%"}} as a single argument:
 *
 * <pre>
 * received [1] {type:object,p:C:\tmp
 * received [2] 100%}
 * </pre>
 *
 * <p>Every quote was consumed as a grouping character, and the argument then split on the space
 * those quotes should have protected. Minifying does not help; quotes are stripped whether or
 * not whitespace is present.
 *
 * <p>This matters because a JSON Schema can only be supplied inline. The CLI rejects a file
 * path with {@code --json-schema is not valid JSON}, so there is no way to route it around the
 * command line the way the prompt is routed through stdin.
 *
 * <p>The fix is to pre-escape so that what the child reconstructs is the original text. The
 * rules the C runtime applies when splitting are:
 *
 * <ul>
 *   <li>{@code \"} produces a literal quote
 *   <li>2n backslashes before a quote produce n backslashes, and the quote toggles quoting
 *   <li>2n+1 backslashes before a quote produce n backslashes and a literal quote
 * </ul>
 *
 * <p>So each quote is emitted as {@code \"}, and any run of backslashes immediately preceding a
 * quote is doubled first. Backslashes elsewhere are left alone.
 */
final class WindowsArgv {

    private WindowsArgv() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Encodes one argument value for the current platform.
     *
     * <p>On every other platform the JVM hands arguments to {@code execvp} as a real array, so
     * nothing is re-parsed and escaping here would corrupt the value instead of protecting it.
     */
    static String encode(String value) {
        return isWindows() ? escapeForWindowsChild(value) : value;
    }

    static String escapeForWindowsChild(String value) {
        if (value.indexOf('"') < 0) {
            // No quotes means nothing to protect. Leaving it untouched also avoids disturbing
            // a value that ends in a backslash, which would otherwise interact badly with the
            // quoting the JVM adds around arguments containing whitespace.
            return value;
        }

        StringBuilder out = new StringBuilder(value.length() + 16);
        int pendingBackslashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                pendingBackslashes++;
                continue;
            }
            if (c == '"') {
                // Double the run so it survives as literal backslashes, then escape the quote.
                out.append("\\".repeat(pendingBackslashes * 2));
                out.append("\\\"");
            } else {
                out.append("\\".repeat(pendingBackslashes));
                out.append(c);
            }
            pendingBackslashes = 0;
        }
        out.append("\\".repeat(pendingBackslashes));
        return out.toString();
    }
}
