package io.github.sshukla154.aido.discussion;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The command surface: start a round, then answer it once a challenger has replied.
 *
 * <p>Two commands rather than one, because the challenger is a person. {@code --start} runs the
 * architect and stops, having written a prompt to paste elsewhere; {@code --answer} picks the round
 * back up from the run directory once a reply exists. Between them the process is not running, so
 * nothing waits on human attention and a laptop can be closed mid-discussion.
 *
 * <p>A command-line surface rather than HTTP on purpose. A round takes minutes of real provider
 * time, which is a batch job, and an HTTP request that waits that long is a request that will be
 * abandoned by something in the middle.
 */
@Component
@Order(100)
public final class DiscussionCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscussionCommand.class);

    private static final String START = "start";
    private static final String ANSWER = "answer";
    private static final String QUESTION = "question";
    private static final String RUN = "run";
    private static final String REPLY = "reply";
    private static final String OUT = "out";

    private final RoundRunner rounds;
    private final DiscussionProperties properties;

    public DiscussionCommand(RoundRunner rounds, DiscussionProperties properties) {
        this.rounds = rounds;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(START)) {
            start(args);
        } else if (args.containsOption(ANSWER)) {
            answer(args);
        }
        // Silence otherwise. Another runner handles --probe, and printing usage on every no-arg
        // start would make the application noisy for callers that only want the context.
    }

    private void start(ApplicationArguments args) {
        Path questionFile = requirePath(args, QUESTION,
                "--start needs --question=<file> pointing at a markdown file with a '# Question' heading");

        RoundRunner.Started started = rounds.start(
                DiscussionQuestion.read(questionFile), properties.model(), properties.effort());

        // Deliberately at INFO and deliberately not the prompt itself. The operator needs to know
        // where to look; the content stays on disk, because a prompt in a log is a second copy of
        // private reasoning in the least controlled place.
        log.info("Round started. Recorded at {}", started.run().runDirectory());
        log.info("Paste this into a challenger chat: {}", started.challengerPromptFile());
        log.info("Then run: --answer --run={} --reply=<their-reply.json>",
                started.run().runDirectory().getFileName());
    }

    private void answer(ApplicationArguments args) {
        Path runDirectory = requirePath(args, RUN,
                "--answer needs --run=<run-directory>, as printed when the round started");
        // Optional on purpose. An automatic round already recorded the challenger turn, and
        // demanding it again as a file is what stopped those rounds ever reaching an artifact.
        Optional<Path> replyFile = optional(args, REPLY).map(Path::of);
        Path artifact = optional(args, OUT)
                .map(Path::of)
                .orElseGet(() -> runDirectory.resolve("discussion.md"));

        Path written = rounds.answer(runDirectory, replyFile, artifact,
                properties.model(), properties.effort());

        log.info("Round complete. Read it here: {}", written);
    }

    private static Path requirePath(ApplicationArguments args, String option, String usage) {
        return Path.of(optional(args, option).orElseThrow(() -> new IllegalArgumentException(usage)));
    }

    private static Optional<String> optional(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        return values == null || values.isEmpty() || values.getFirst().isBlank()
                ? Optional.empty()
                : Optional.of(values.getFirst());
    }
}
