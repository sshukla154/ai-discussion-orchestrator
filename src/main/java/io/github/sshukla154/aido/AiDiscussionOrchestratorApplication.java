package io.github.sshukla154.aido;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Entry point. Deliberately a non-web application: there is no HTTP surface yet, and
 * introducing one before the provider layer is durable would invite a request thread to block
 * on a subprocess that can legitimately run for five minutes.
 */
@SpringBootApplication
public class AiDiscussionOrchestratorApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AiDiscussionOrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
