package io.github.sshukla154.aido.archfixture;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import io.github.sshukla154.aido.provider.claude.ClaudeCliClient;
import io.github.sshukla154.aido.provider.claude.CliRequest;
import io.github.sshukla154.aido.provider.claude.CliResultParser;

/**
 * Transactional at method level only, and with default propagation.
 *
 * <p>Exists because the sibling fixture is annotated at class level, which trips the rule before
 * the per-method loop is ever reached -- so that loop was never actually proven to work. This is
 * also the more realistic shape: a service class where one method opens a transaction.
 *
 * <p>Never move to main sources.
 */
public class MethodOnlyTransactionalCaller {

    private final ClaudeCliClient client =
            new ClaudeCliClient(List.of("claude"), Path.of("."), new CliResultParser());

    @Transactional
    public void wrapsASubprocessCallInATransaction() {
        client.run(CliRequest.stateless("anything", Duration.ofSeconds(1)));
    }
}
