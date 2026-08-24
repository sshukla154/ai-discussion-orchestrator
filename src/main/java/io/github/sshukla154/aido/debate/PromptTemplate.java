package io.github.sshukla154.aido.debate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt loaded from a classpath resource, rendered by literal placeholder substitution.
 *
 * <p>Deliberately not a templating engine. Prompts are this application's source code, so they
 * live in versioned files and are rendered by the simplest mechanism that cannot surprise anyone:
 * exact replacement of {@code {{name}}} with a caller-supplied string. An engine would bring
 * expression evaluation into a document that is assembled partly from untrusted model output,
 * which is the last place it belongs.
 *
 * <p>Two guarantees the substitution must provide, both enforced here rather than trusted:
 *
 * <ol>
 *   <li><b>No unresolved placeholder reaches a model.</b> A stray {@code {{roundQuestion}}} in a
 *       sent prompt is a silent defect -- the model answers a question with a literal brace in it
 *       and nothing reports a problem.
 *   <li><b>No substituted value can forge the untrusted-content boundary.</b> Foreign content is
 *       fenced by a marker that tells the model to treat the block as data rather than
 *       instructions. A value containing that marker could close the fence early and have the
 *       remainder read as template text, which is the injection this fencing exists to stop.
 * </ol>
 */
public final class PromptTemplate {

    /**
     * The fence marker, matched as a prefix so both the begin and end forms are covered.
     *
     * <p>Kept in sync with the templates by a test rather than by convention: a renamed marker
     * that this guard no longer recognises would disable the check while every template still
     * looked correct.
     */
    static final String UNTRUSTED_MARKER = "[[[UNTRUSTED";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}");

    private final String id;
    private final String text;

    private PromptTemplate(String id, String text) {
        this.id = id;
        this.text = text;
    }

    /**
     * Loads a template from {@code prompts/<name>.md} on the classpath.
     *
     * @throws IllegalArgumentException if the resource is absent, which is a packaging error
     *                                 rather than a runtime condition worth recovering from
     */
    public static PromptTemplate load(String name) {
        String resource = "prompts/" + name + ".md";
        try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("prompt template not on the classpath: " + resource);
            }
            return new PromptTemplate(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read prompt template " + resource, e);
        }
    }

    public String id() {
        return id;
    }

    /** The unrendered source. Hashed alongside a turn so the builder version is recoverable. */
    public String source() {
        return text;
    }

    /** Placeholder names this template expects, in first-appearance order. */
    public Set<String> placeholders() {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Renders the template, failing rather than degrading on any of the three ways this can go
     * wrong: a value that could forge the content fence, a placeholder with no value, and a value
     * supplied for a placeholder this template does not have.
     *
     * <p>The third is the mildest, and is still an error. It almost always means a template and
     * its caller have drifted apart, and the caller believes it supplied something the model never
     * saw.
     */
    public String render(Map<String, String> values) {
        rejectForgedFences(values);

        Set<String> expected = placeholders();
        List<String> missing = new ArrayList<>();
        for (String name : expected) {
            if (values.get(name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "template " + id + " has no value for " + missing
                            + "; render an explicit placeholder such as \"none stated\" rather than "
                            + "leaving a label dangling");
        }

        List<String> unexpected = values.keySet().stream().filter(k -> !expected.contains(k)).sorted().toList();
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                    "template " + id + " has no placeholder for " + unexpected
                            + "; the caller and the template have drifted apart");
        }

        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 512);
        while (m.find()) {
            // Quoted so a value containing $1 or a backslash is inserted literally rather than
            // being read as a replacement expression.
            m.appendReplacement(out, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private void rejectForgedFences(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String value = e.getValue();
            if (value != null && value.contains(UNTRUSTED_MARKER)) {
                // Refused rather than stripped or escaped. Any transformation here changes content
                // that is recorded verbatim elsewhere, and a mismatch between what was recorded
                // and what was sent defeats the provenance record. The caller should reject the
                // input at ingest instead, where it can tell the human what happened.
                throw new IllegalArgumentException(
                        "value for '" + e.getKey() + "' contains the untrusted-content marker "
                                + UNTRUSTED_MARKER + " and could forge the content boundary; "
                                + "reject it at ingest rather than rendering it");
            }
        }
    }
}
