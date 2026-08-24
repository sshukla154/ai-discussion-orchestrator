package io.github.sshukla154.aido.provenance;

import java.nio.file.Path;

/** Immutable reference to a turn whose request has been recorded but whose result has not. */
public record TurnHandle(RunId runId, int sequence, String turnId, Path turnDirectory) {
}
