package org.pipesor.pipeline;

import java.time.Clock;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class GitHubConnectorTestConfiguration {
    @Bean @Primary
    GitHubConnectorDouble explicitGitHubFacts(Clock clock) { return new GitHubConnectorDouble(clock); }

    @Bean @Primary
    CaptureScopeSyncRecorder recordedCaptureScopeSync(@Qualifier("captureScopeSync") CaptureScopeSync real) {
        return new CaptureScopeSyncRecorder(real);
    }
}
