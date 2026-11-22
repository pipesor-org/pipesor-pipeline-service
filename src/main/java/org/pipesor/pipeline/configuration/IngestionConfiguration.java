package org.pipesor.pipeline.configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.infrastructure.capture.HttpCaptureScopeSync;
import org.pipesor.pipeline.catalog.infrastructure.github.CachingGitHubConnector;
import org.pipesor.pipeline.catalog.infrastructure.github.IngestionGitHubConnector;
import org.pipesor.pipeline.execution.infrastructure.access.HttpIngestionScopeAuthority;
import org.pipesor.pipeline.shared.interfaces.rest.CorrelationFilter;
import org.pipesor.pipeline.shared.security.SubjectTokens;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.pipesor.pipeline.shared.security.WorkloadTokens;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Outbound calls to Ingestion, authenticated with this workload's tokens (route catalog, pipeline → ingestion). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IngestionClientProperties.class, OwnerValidationProperties.class})
public class IngestionConfiguration {

    @Bean
    GitHubConnector ingestionGitHubConnector(IngestionClientProperties settings, TransportPolicy transport,
            WorkloadTokens tokens, Clock clock) {
        var live = new IngestionGitHubConnector(client(settings), transport, settings.baseUri(),
                () -> tokens.issue("ingestion", IngestionGitHubConnector.ROLE), SubjectTokens::currentSession,
                IngestionConfiguration::correlation, settings.requestTimeout(), settings.decisionBudget(), settings.maxResponseBytes(),
                settings.maxBatchSize(), clock);
        return new CachingGitHubConnector(live, settings.viewAccessCacheTtl(), clock);
    }

    @Bean
    HttpIngestionScopeAuthority ingestionScopeAuthority(IngestionClientProperties settings, OwnerValidationProperties limits,
            TransportPolicy transport, WorkloadTokens tokens, Clock clock) {
        return new HttpIngestionScopeAuthority(client(settings), transport, settings.baseUri(),
                () -> tokens.issue("ingestion", HttpIngestionScopeAuthority.ROLE), limits.maxResponseBytes(), clock);
    }

    @Bean
    CaptureScopeSync captureScopeSync(IngestionClientProperties settings, TransportPolicy transport,
            WorkloadTokens tokens, Clock clock) {
        return new HttpCaptureScopeSync(client(settings), transport, settings.baseUri(),
                () -> tokens.issue("ingestion", HttpCaptureScopeSync.ROLE), settings.requestTimeout(),
                settings.maxResponseBytes(), clock);
    }

    private static HttpClient client(IngestionClientProperties settings) {
        return HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String correlation() {
        var request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(CorrelationFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof String id) {
            return id;
        }
        return java.util.UUID.randomUUID().toString();
    }
}
