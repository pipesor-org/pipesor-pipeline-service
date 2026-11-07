package org.pipesor.pipeline.configuration;

import java.time.Clock;
import java.util.function.Supplier;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.services.GitHubAccessChecks;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationSession;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@Configuration(proxyBeanMethods = false)
public class GitHubAccessConfiguration {
    @Bean
    @ConditionalOnMissingBean(GitHubConnector.class)
    GitHubConnector unavailableGitHubConnector() {
        return new GitHubConnector() {
            @Override public Access verifyAccess(AccessRequest request) { throw new GitHubVerificationUnavailable(); }
            @Override public Workflow verifyWorkflow(String actor, String repository, String workflow) {
                throw new GitHubVerificationUnavailable();
            }
        };
    }

    @Bean
    GitHubAccessChecks gitHubAccessChecks(GitHubConnector connector, Clock clock) {
        Supplier<GitHubVerificationSession> sessions = () -> {
            var request = RequestContextHolder.getRequestAttributes();
            if (request == null) { return new GitHubVerificationSession(); }
            var key = GitHubVerificationSession.class.getName();
            var existing = request.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (existing instanceof GitHubVerificationSession session) { return session; }
            var session = new GitHubVerificationSession();
            request.setAttribute(key, session, RequestAttributes.SCOPE_REQUEST);
            return session;
        };
        return new GitHubAccessChecks(connector, clock, sessions);
    }

    @Bean
    HumanPipelineAccess humanPipelineAccess(PipelineAccess grants, PipelineLookup pipelines, GitHubAccessChecks github) {
        return new HumanPipelineAccess(grants, pipelines, github);
    }
}
