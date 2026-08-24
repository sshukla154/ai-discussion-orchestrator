package io.github.sshukla154.aido.discussion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscussionQuestionTest {

    @Test
    @DisplayName("all three sections are read, and constraints become individual points")
    void parsesAllSections() {
        DiscussionQuestion q = DiscussionQuestion.parse("""
                # Question
                Shard by tenant or by region?

                # Objective
                Pick one and know the trade.

                # Constraints
                - single-writer SQLite
                * no cross-region transactions
                """);

        assertThat(q.question()).isEqualTo("Shard by tenant or by region?");
        assertThat(q.objective()).isEqualTo("Pick one and know the trade.");
        assertThat(q.constraints())
                .containsExactly("single-writer SQLite", "no cross-region transactions");
    }

    @Test
    @DisplayName("absent optional sections render an explicit placeholder, never a blank label")
    void missingSectionsBecomePlaceholders() {
        // A prompt containing "Objective:" followed by nothing reads to a model as an objective
        // deliberately left empty, which is not what an omitted section means.
        DiscussionQuestion q = DiscussionQuestion.parse("# Question\nIs this enough?\n");

        assertThat(q.objective()).isEqualTo(DiscussionQuestion.NONE_STATED);
        assertThat(q.renderConstraints()).isEqualTo(DiscussionQuestion.NONE_STATED);
    }

    @Test
    @DisplayName("a non-bullet line under Constraints is still a constraint")
    void unbulletedConstraintIsKept() {
        // Dropping it for lacking a dash would silently remove a requirement from every prompt.
        DiscussionQuestion q = DiscussionQuestion.parse("""
                # Question
                Anything?

                # Constraints
                must run offline
                """);

        assertThat(q.constraints()).containsExactly("must run offline");
    }

    @Test
    @DisplayName("a multi-paragraph question keeps its shape, including code")
    void preservesMultilineQuestions() {
        DiscussionQuestion q = DiscussionQuestion.parse("""
                # Question
                Given this:

                ```java
                var x = 1;
                ```

                what breaks?
                """);

        assertThat(q.question()).contains("```java").contains("var x = 1;").contains("what breaks?");
    }

    @Test
    @DisplayName("a file with no Question heading fails and says what was found instead")
    void missingQuestionHeadingIsRejected() {
        assertThatThrownBy(() -> DiscussionQuestion.parse("# Objective\nsomething\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("# Question")
                .hasMessageContaining("objective");
    }

    @Test
    @DisplayName("text before the first heading is ignored rather than promoted to the question")
    void preambleIsIgnored() {
        // Guessing here would let a file whose author forgot the headings run with its preamble
        // silently treated as the question.
        assertThatThrownBy(() -> DiscussionQuestion.parse("just some notes with no headings"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no headings at all");
    }

    @Test
    @DisplayName("an empty question is rejected")
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> DiscussionQuestion.parse("# Question\n\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a question");
    }

    @Test
    @DisplayName("headings are matched case-insensitively, since a human writes this file")
    void headingsAreCaseInsensitive() {
        DiscussionQuestion q = DiscussionQuestion.parse("# QUESTION\nfine?\n\n# objective\nnone\n");

        assertThat(q.question()).isEqualTo("fine?");
        assertThat(q.objective()).isEqualTo("none");
    }
}
