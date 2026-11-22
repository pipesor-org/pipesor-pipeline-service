package org.pipesor.pipeline;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync.Outcome;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.CaptureScopeReconciliation;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-023 call sites with real PostgreSQL and HTTP: the scope is synced after the registration or
 * status change commits, never inside the transaction, and a failed sync never fails the response.
 */
@Import(GitHubConnectorTestConfiguration.class)
class CaptureScopeSyncTests extends PostgresIntegrationTest {
    private static final String REPO = "823071";
    private static final String WORKFLOW = "918071";
    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubConnectorDouble github;
    @Autowired CaptureScopeSyncRecorder scopes;
    @Autowired CaptureScopeReconciliation reconciliation;

    @BeforeEach
    void verifiedSelection() {
        github.reset();
        scopes.reset();
        github.allow(ApiContractTests.PRINCIPAL, REPO, true);
        github.workflow(ApiContractTests.PRINCIPAL, REPO, WORKFLOW, "verified/scope", "main", "Scope build", ".github/workflows/scope.yml");
    }

    @AfterEach
    void cleanup() {
        scopes.reset();
        for (UUID id : jdbc.queryForList("SELECT p.id FROM ops.pipelines p JOIN ops.repositories r ON p.repository_id = r.id WHERE r.github_repository_id = ?", UUID.class, REPO)) {
            jdbc.update("DELETE FROM audit.audit_events WHERE entity_id = ?", id.toString());
            jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id = ?", id);
            jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM ops.repositories WHERE github_repository_id = ?", REPO);
    }

    @Test
    void registrationIsSyncedAfterCommitAndStays201WhenIngestionIsUnavailable() {
        var created = register();
        assertThat(created.id()).isEqualTo(DeterministicIds.pipeline(REPO, WORKFLOW));
        assertThat(scopes.calls).singleElement().satisfies(call -> {
            assertThat(call.scope().githubRepositoryId()).isEqualTo(REPO);
            assertThat(call.scope().githubWorkflowId()).isEqualTo(WORKFLOW);
            assertThat(call.scope().pipelineId()).isEqualTo(created.id());
            assertThat(call.scope().status()).isEqualTo(MonitoringStatus.ACTIVE);
            assertThat(call.correlationId()).isNotBlank();
            assertThat(call.insideTransaction()).isFalse();
            assertThat(call.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipelines WHERE id = ?", Integer.class, created.id())).isEqualTo(1);
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void unexpectedSyncDefectDoesNotFailOrRollBackTheRegistration() {
        scopes.explode = true;
        var created = register();
        assertThat(scopes.calls).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipelines WHERE id = ?", Integer.class, created.id())).isEqualTo(1);
    }

    @Test
    void onlyCommittedMonitoringStatusChangesAreSynced() {
        var created = register();
        scopes.reset();
        var path = "/api/v1/pipelines/" + created.id();
        client.put().uri(path + "/policy").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("criticality", "LOW", "monitoringStatus", "PAUSED", "environment", "dev"))
                .exchange().expectStatus().isOk();
        assertThat(scopes.calls).singleElement().satisfies(call -> {
            assertThat(call.scope().status()).isEqualTo(MonitoringStatus.PAUSED);
            assertThat(call.scope().pipelineId()).isEqualTo(created.id());
            assertThat(call.insideTransaction()).isFalse();
        });
        // Same status again and an ownership-only change: nothing to sync.
        client.put().uri(path + "/policy").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("criticality", "HIGH", "monitoringStatus", "PAUSED", "environment", "dev"))
                .exchange().expectStatus().isOk();
        client.put().uri(path + "/ownership").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("responsibleTeamRef", "team-x", "responsibleTeamNameSnapshot", "X"))
                .exchange().expectStatus().isOk();
        assertThat(scopes.calls).hasSize(1);
        client.put().uri(path + "/settings").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("criticality", "HIGH", "monitoringStatus", "ACTIVE"))
                .exchange().expectStatus().isOk();
        assertThat(scopes.calls).hasSize(2);
        assertThat(scopes.calls.get(1).scope().status()).isEqualTo(MonitoringStatus.ACTIVE);
    }

    @Test
    void reconciliationRepeatsTheCommittedStatusOfRegisteredPipelines() {
        var created = register();
        jdbc.update("UPDATE ops.pipelines SET monitoring_status = 'PAUSED' WHERE id = ?", created.id());
        scopes.reset();
        // Ingestion is a closed port here: the pass stops at the first unavailable outcome.
        var unavailable = reconciliation.run("reconcile-down");
        assertThat(unavailable.status()).isEqualTo(CaptureScopeReconciliation.Status.ABORTED_UNAVAILABLE);
        assertThat(scopes.calls).hasSize(1);
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();

        scopes.reset();
        scopes.stub = scope -> new CaptureScopeSync.Result(Outcome.SYNCED, "rev-1", 1L);
        var report = reconciliation.run("reconcile-up");
        assertThat(report.status()).isEqualTo(CaptureScopeReconciliation.Status.COMPLETED);
        assertThat(report.attempted()).isEqualTo(report.synced()).isEqualTo(scopes.calls.size());
        assertThat(reconciliation.last()).isEqualTo(report);
        assertThat(scopes.calls).allSatisfy(call -> {
            assertThat(call.insideTransaction()).isFalse();
            assertThat(call.correlationId()).isEqualTo("reconcile-up");
        });
        List<UUID> ids = scopes.calls.stream().map(call -> call.scope().pipelineId()).toList();
        assertThat(ids).doesNotHaveDuplicates().contains(created.id());
        assertThat(scopes.calls).filteredOn(call -> call.scope().pipelineId().equals(created.id())).singleElement()
                .satisfies(call -> assertThat(call.scope().status()).isEqualTo(MonitoringStatus.PAUSED));
    }

    private PipelineView register() {
        return client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("githubRepositoryId", REPO, "githubWorkflowId", WORKFLOW, "criticality", "HIGH",
                        "monitoringStatus", "ACTIVE"))
                .exchange().expectStatus().isCreated().expectBody(PipelineView.class).returnResult().getResponseBody();
    }
}
