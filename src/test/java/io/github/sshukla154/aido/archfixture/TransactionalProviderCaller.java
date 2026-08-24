package io.github.sshukla154.aido.archfixture;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.sshukla154.aido.provider.claude.ClaudeCliClient;
import io.github.sshukla154.aido.provider.claude.CliRequest;
import io.github.sshukla154.aido.provider.claude.CliResultParser;

/**
 * Deliberately violates every rule in {@code ArchitectureTest}, so those rules can be shown to
 * actually fail rather than merely pass over an empty set.
 *
 * <p>This exists because an architecture rule with nothing to match is the most dangerous shape a
 * test can take: green, and worthless. The rules themselves run against production classes only,
 * where this fixture is invisible.
 *
 * <p>Never move to main sources.
 */
@Transactional
public class TransactionalProviderCaller {

    private final ClaudeCliClient client =
            new ClaudeCliClient(List.of("claude"), Path.of("."), new CliResultParser());

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void holdsATransactionAcrossASubprocess() {
        client.run(CliRequest.stateless("anything", Duration.ofSeconds(1)));
    }
}
