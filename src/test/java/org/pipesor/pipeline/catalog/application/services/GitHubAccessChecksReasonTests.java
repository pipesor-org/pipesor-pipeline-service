package org.pipesor.pipeline.catalog.application.services;

import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Access;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.AccessRequest;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Operation;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.RepositoryAccess;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Workflow;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable.Reason;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every unusable GitHub fact names a fixed reason that the HTTP boundary logs. */
class GitHubAccessChecksReasonTests {
    private static final String ACTOR = "11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void namesExpiredFactsSeparatelyFromBindingMismatches() {
        assertReason(facts(NOW.minusSeconds(20), NOW.minusSeconds(1), ACTOR, "823001"), Reason.FACTS_EXPIRED);
        assertReason(facts(NOW.minusSeconds(2), NOW.plusSeconds(8), "other:actor", "823001"), Reason.FACTS_BINDING_MISMATCH);
        assertReason(facts(NOW.minusSeconds(2), NOW.plusSeconds(8), ACTOR, "999999"), Reason.FACTS_BINDING_MISMATCH);
    }

    @Test
    void namesConnectorFailuresAndKeepsConnectionRequiredAsSuch() {
        var unreachable = checks(request -> { throw new IllegalStateException("socket"); });
        assertThatThrownBy(() -> unreachable.verify(ACTOR, ACTOR, Set.of("823001"), Operation.VIEW))
                .isInstanceOfSatisfying(GitHubVerificationUnavailable.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.CONNECTOR_UNREACHABLE));
        var reported = checks(request -> { throw new GitHubVerificationUnavailable(Reason.RESPONSE_INVALID); });
        assertThatThrownBy(() -> reported.verify(ACTOR, ACTOR, Set.of("823001"), Operation.VIEW))
                .isInstanceOfSatisfying(GitHubVerificationUnavailable.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.RESPONSE_INVALID));
        var disconnected = checks(request -> { throw new GitHubConnectionRequired(); });
        assertThatThrownBy(() -> disconnected.verify(ACTOR, ACTOR, Set.of("823001"), Operation.VIEW))
                .isInstanceOf(GitHubConnectionRequired.class);
    }

    @Test
    void namesIncompleteWorkflowFactsOnRegistration() {
        var access = facts(NOW.minusSeconds(2), NOW.plusSeconds(8), ACTOR, "823001");
        var checks = new GitHubAccessChecks(new GitHubConnector() {
            @Override public Access verifyAccess(AccessRequest request) { return access; }
            @Override public Workflow verifyWorkflow(String actor, String repository, String workflow) {
                return new Workflow(access, repository, workflow, "org/repo", "main", "", ".github/workflows/ci.yml");
            }
        }, CLOCK, GitHubVerificationSession::new);
        assertThatThrownBy(() -> checks.registration(ACTOR, "823001", "918001"))
                .isInstanceOfSatisfying(GitHubVerificationUnavailable.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.WORKFLOW_FACTS_INCOMPLETE));
    }

    private static void assertReason(Access facts, Reason expected) {
        assertThatThrownBy(() -> checks(request -> facts).verify(ACTOR, ACTOR, Set.of("823001"), Operation.VIEW))
                .isInstanceOfSatisfying(GitHubVerificationUnavailable.class,
                        failure -> assertThat(failure.reason()).isEqualTo(expected));
    }

    private static GitHubAccessChecks checks(Function<AccessRequest, Access> verify) {
        return new GitHubAccessChecks(new GitHubConnector() {
            @Override public Access verifyAccess(AccessRequest request) { return verify.apply(request); }
            @Override public Workflow verifyWorkflow(String actor, String repository, String workflow) {
                throw new UnsupportedOperationException();
            }
        }, CLOCK, GitHubVerificationSession::new);
    }

    private static Access facts(Instant verifiedAt, Instant validUntil, String actor, String repository) {
        return new Access(actor, actor, Operation.VIEW, "87343021", true, verifiedAt, validUntil,
                Map.of(repository, new RepositoryAccess(repository, "5001", "7001", true, true, false)));
    }
}
