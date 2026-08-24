package io.github.sshukla154.aido.provenance;

import java.nio.file.Path;

/** Immutable reference to an open run. The store keeps no per-run state of its own. */
public record RunHandle(RunId runId, Path runDirectory) {
}
