package io.github.sshukla154.aido.provenance;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param runRoot where run records are written. Outside the repository by default: this is a
 *                public repository and the records contain the user's private reasoning.
 */
@ConfigurationProperties("aido.provenance")
public record ProvenanceProperties(Path runRoot) {
}
