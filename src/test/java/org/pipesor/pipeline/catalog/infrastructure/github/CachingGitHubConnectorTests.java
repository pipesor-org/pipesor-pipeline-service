package org.pipesor.pipeline.catalog.infrastructure.github;

import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Access;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.AccessRequest;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Operation;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.RepositoryAccess;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Workflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingGitHubConnectorTests {
    private static final Instant T0 = Instant.parse("2026-11-22T12:00:00Z");
    private Instant now = T0;
    private int calls;
    private RuntimeException failure;
    private boolean linked = true;

    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final GitHubConnector live = new GitHubConnector() {
        @Override public Access verifyAccess(AccessRequest request) {
            calls++;
            if (failure != null) throw failure;
            return new Access(request.actorRef(), request.subjectRef(), request.operation(), "42", linked, now,
                    now.plusSeconds(5), Map.of("1", new RepositoryAccess("1", "9", true, true, false)));
        }
        @Override public Workflow verifyWorkflow(String actorRef, String repositoryId, String workflowId) {
            calls++;
            return null;
        }
    };
    private final CachingGitHubConnector cache = new CachingGitHubConnector(live, Duration.ofSeconds(30), clock);

    private static AccessRequest request(Operation operation) {
        return new AccessRequest("t:actor", "t:actor", Set.of("1"), operation);
    }

    @Test
    void reusesAViewVerificationUntilItsTtlAndThenVerifiesLive() {
        var first = cache.verifyAccess(request(Operation.VIEW));
        now = T0.plusSeconds(29);
        var reused = cache.verifyAccess(request(Operation.VIEW));
        assertThat(calls).isEqualTo(1);
        assertThat(reused.verifiedAt()).isEqualTo(first.verifiedAt());
        assertThat(reused.validUntil()).isEqualTo(T0.plusSeconds(30));
        now = T0.plusSeconds(30);
        cache.verifyAccess(request(Operation.VIEW));
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void commandsAndRegistrationAlwaysVerifyLive() {
        for (var operation : Set.of(Operation.OPERATE, Operation.CONFIGURE, Operation.REGISTER, Operation.GRANT_OPERATE)) {
            cache.verifyAccess(request(operation));
            cache.verifyAccess(request(operation));
        }
        cache.verifyWorkflow("t:actor", "1", "2");
        assertThat(calls).isEqualTo(9);
    }

    @Test
    void neverCachesFailuresOrUnlinkedFacts() {
        failure = new IllegalStateException("unavailable");
        assertThatThrownBy(() -> cache.verifyAccess(request(Operation.VIEW))).isSameAs(failure);
        failure = null;
        linked = false;
        cache.verifyAccess(request(Operation.VIEW));
        linked = true;
        cache.verifyAccess(request(Operation.VIEW));
        cache.verifyAccess(request(Operation.VIEW));
        assertThat(calls).isEqualTo(3);
    }

    @Test
    void keysByActorSubjectRepositoriesAndOperation() {
        cache.verifyAccess(request(Operation.VIEW));
        cache.verifyAccess(new AccessRequest("t:other", "t:other", Set.of("1"), Operation.VIEW));
        cache.verifyAccess(new AccessRequest("t:actor", "t:actor", Set.of("1", "2"), Operation.VIEW));
        assertThat(calls).isEqualTo(3);
    }

    @Test
    void rejectsUnboundedTtls() {
        assertThatThrownBy(() -> new CachingGitHubConnector(live, Duration.ofSeconds(61), clock))
                .isInstanceOf(IllegalArgumentException.class);
        var disabled = new CachingGitHubConnector(live, Duration.ZERO, clock);
        disabled.verifyAccess(request(Operation.VIEW));
        disabled.verifyAccess(request(Operation.VIEW));
        assertThat(calls).isEqualTo(2);
    }
}
