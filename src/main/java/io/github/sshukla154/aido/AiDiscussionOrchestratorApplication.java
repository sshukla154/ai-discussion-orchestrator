package io.github.sshukla154.aido;

import java.time.Clock;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Entry point. Deliberately a non-web application: there is no HTTP surface yet, and
 * introducing one before the provider layer is durable would invite a request thread to block
 * on a subprocess that can legitimately run for five minutes.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiDiscussionOrchestratorApplication {

    /**
     * UTC, because every recorded timestamp is UTC and a local-zone clock would make the record
     * disagree with itself. Boot defines no Clock bean, so this is required rather than optional.
     */
    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(AiDiscussionOrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
