package io.github.sshukla154.aido.discussion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which model argues, and how hard it thinks.
 *
 * <p>Configurable rather than hardcoded because the right answer depends on the question. A
 * throwaway comparison does not need the expensive model, and an architecture decision does. Both
 * values are recorded with every turn, so a saved discussion says what actually produced it --
 * without that, comparing two runs is meaningless.
 *
 * @param model  a CLI alias such as {@code opus} or {@code sonnet}, resolved by the CLI itself
 *               rather than validated here. The account's available models are not enumerable, so
 *               a wrong value surfaces as a clear pre-flight error rather than a silent fallback.
 * @param effort one of low, medium, high, xhigh, max
 */
@ConfigurationProperties("aido.discussion")
public record DiscussionProperties(String model, String effort) {

    public DiscussionProperties {
        model = model == null || model.isBlank() ? "sonnet" : model;
        effort = effort == null || effort.isBlank() ? "high" : effort;
    }
}
