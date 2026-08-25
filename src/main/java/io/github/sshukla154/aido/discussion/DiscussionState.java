package io.github.sshukla154.aido.discussion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.sshukla154.aido.provenance.AtomicFiles;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enough state to resume a round after the process has exited.
 *
 * <p>The challenger is a person pasting into a separate chat, so a round cannot be one continuous
 * command -- its second half happens minutes or hours later, in a new JVM. This file is what
 * bridges that gap. It lives in the run directory beside the provenance record and is written once
 * per phase.
 *
 * <p>Only the raw structured payloads are stored, not the derived {@link ClaimLedger}. The ledger
 * is a pure fold over turns, so recomputing it is cheap and cannot disagree with the turns it came
 * from. Storing it as well would create a second version of the truth that could drift.
 *
 * <p>Distinct from the provenance record on purpose. That record is forensic and never re-read by
 * the application; this one is operational. Reading the forensic copy back to resume would couple
 * the resume path to a file format whose whole value is being an untouched artifact.
 */
record DiscussionState(
        String question,
        String objective,
        List<String> constraints,
        Map<String, Object> architectTurn,
        Map<String, Object> challengerTurn) {

    private static final String FILE_NAME = "discussion-state.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    DiscussionState {
        constraints = List.copyOf(constraints);
        architectTurn = Map.copyOf(architectTurn);
        challengerTurn = challengerTurn == null ? null : Map.copyOf(challengerTurn);
    }

    DiscussionQuestion asQuestion() {
        return new DiscussionQuestion(question, objective, constraints);
    }

    /**
     * Written atomically, and written more than once per run.
     *
     * <p>This is the only file the resume path reads, so a torn write is the difference between a run
     * that can be finished by hand and one that is lost. A plain write leaves truncated JSON if the
     * process dies mid-flush; temp-then-move keeps the previous version intact until the new one is
     * complete.
     */
    void writeTo(Path runDirectory) {
        try {
            AtomicFiles.writeString(runDirectory.resolve(FILE_NAME), MAPPER.writeValueAsString(this));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + FILE_NAME + " to " + runDirectory, e);
        }
    }

    static DiscussionState readFrom(Path runDirectory) {
        Path file = runDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    "no resumable discussion at " + runDirectory + "; expected " + FILE_NAME
                            + ". Start a round before answering one.");
        }
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), DiscussionState.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    DiscussionState withChallengerTurn(Map<String, Object> turn) {
        return new DiscussionState(question, objective, constraints, architectTurn, turn);
    }
}
