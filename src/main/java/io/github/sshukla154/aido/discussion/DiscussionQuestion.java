package io.github.sshukla154.aido.discussion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the discussion is about, read from a file the human wrote.
 *
 * <p>A file rather than a command-line argument for two reasons. A real architecture question is
 * multi-paragraph and often contains code, which argv handles badly on Windows. And the file is
 * the thing that gets recorded and re-read months later, so it should exist independently of a
 * shell history.
 *
 * <p>Format is deliberately plain markdown with three level-one headings, so it can be written
 * and diffed without tooling:
 *
 * <pre>
 * # Question
 * Should we shard by tenant or by region?
 *
 * # Objective
 * Pick one and know what we are trading away.
 *
 * # Constraints
 * - Single-writer SQLite, one connection
 * - No cross-region transactions
 * </pre>
 *
 * <p>{@code Question} is required. The other two are optional and render as an explicit
 * placeholder rather than an empty string, because a prompt with a dangling "Objective:" label
 * reads to a model as an objective that was deliberately left blank.
 */
public record DiscussionQuestion(String question, String objective, List<String> constraints) {

    /** Rendered where a section is absent, so no label in a prompt dangles. */
    public static final String NONE_STATED = "none stated";

    private static final String QUESTION = "question";
    private static final String OBJECTIVE = "objective";
    private static final String CONSTRAINTS = "constraints";

    public DiscussionQuestion {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("a discussion needs a question");
        }
        constraints = List.copyOf(constraints);
    }

    public static DiscussionQuestion read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the question file " + file, e);
        }
    }

    static DiscussionQuestion parse(String markdown) {
        Map<String, List<String>> sections = splitOnHeadings(markdown);

        List<String> questionLines = sections.get(QUESTION);
        if (questionLines == null) {
            throw new IllegalArgumentException(
                    "the question file needs a '# Question' heading; found "
                            + (sections.isEmpty() ? "no headings at all" : sections.keySet()));
        }

        return new DiscussionQuestion(
                joinParagraph(questionLines),
                sections.containsKey(OBJECTIVE) ? joinParagraph(sections.get(OBJECTIVE)) : NONE_STATED,
                bulletPoints(sections.getOrDefault(CONSTRAINTS, List.of())));
    }

    /** Constraints as a prompt-ready block, or an explicit placeholder when there are none. */
    public String renderConstraints() {
        return constraints.isEmpty()
                ? NONE_STATED
                : constraints.stream().map(c -> "- " + c).reduce((a, b) -> a + "\n" + b).orElse(NONE_STATED);
    }

    private static Map<String, List<String>> splitOnHeadings(String markdown) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String line : markdown.split("\r?\n", -1)) {
            if (line.startsWith("# ")) {
                current = line.substring(2).trim().toLowerCase(Locale.ROOT);
                sections.putIfAbsent(current, new ArrayList<>());
            } else if (current != null) {
                sections.get(current).add(line);
            }
            // Content before the first heading is ignored rather than guessed at. A file whose
            // author forgot the headings should fail loudly below, not have its preamble silently
            // promoted to the question.
        }
        return sections;
    }

    private static String joinParagraph(List<String> lines) {
        return String.join("\n", lines).strip();
    }

    private static List<String> bulletPoints(List<String> lines) {
        List<String> points = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                points.add(trimmed.substring(2).strip());
            } else if (!trimmed.isEmpty()) {
                // A non-bullet line inside Constraints is still a constraint. Dropping it because
                // it lacked a dash would silently remove a requirement from every prompt.
                points.add(trimmed);
            }
        }
        return points;
    }
}
