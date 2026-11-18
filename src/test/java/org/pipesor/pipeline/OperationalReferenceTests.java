package org.pipesor.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceAuthority.Permission;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup.Membership;
import org.pipesor.pipeline.execution.application.queryhandlers.ValidateOperationalReferences.Decision;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup;
import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;
import org.pipesor.pipeline.execution.application.queryhandlers.ValidateOperationalReferences;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalReferenceTests extends PostgresIntegrationTest {
    private static final String REPOSITORY = "91001";
    private static final String WORKFLOW = "71001";
    private static final String RUN = "81001";
    private static final UUID PIPELINE = DeterministicIds.pipeline(REPOSITORY, WORKFLOW);
    private static final UUID EXECUTION = DeterministicIds.execution(REPOSITORY, RUN, 1);
    private static final UUID INCIDENT = DeterministicIds.incident(EXECUTION);
    private static final UUID TENANT = UUID.fromString("29735d8d-f832-487a-869a-e5298a6d350b");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionSnapshotService snapshots;
    @Autowired OperationalReferenceLookup lookup;
    @Autowired PlatformTransactionManager transactions;
    private UUID repository;

    @BeforeEach
    void seed() {
        repository = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, 'scope/test', now())", repository, REPOSITORY);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                    criticality, monitoring_status, created_at)
                VALUES (?, ?, ?, 'Scope test', 'scope.yml', 'HIGH', 'ACTIVE', now())
                """, PIPELINE, repository, WORKFLOW);
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM ops.execution_event_receipts WHERE correlation_id = 'owner-reference-test'");
            jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id = ?", INCIDENT);
            jdbc.update("DELETE FROM ops.incidents WHERE id = ?", INCIDENT);
            jdbc.update("DELETE FROM ops.execution_stages WHERE execution_id = ?", EXECUTION);
            jdbc.update("DELETE FROM ops.pipeline_executions WHERE id = ?", EXECUTION);
            jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", PIPELINE);
            jdbc.update("DELETE FROM ops.repositories WHERE id = ?", repository);
        });
    }

    @Test
    void waitsForCommittedOriginAndRevalidatesEachAttempt() {
        var checks = new AtomicInteger();
        var handler = new ValidateOperationalReferences(query -> {
            assertThat(query).isEqualTo(query());
            checks.incrementAndGet();
            return Permission.AUTHORIZED;
        }, lookup);
        assertThat(handler.handle(query())).isEqualTo(Decision.NOT_READY);
        publishFailedSnapshot();
        assertThat(handler.handle(query())).isEqualTo(Decision.AUTHORIZED);
        assertThat(handler.handle(query())).isEqualTo(Decision.AUTHORIZED);
        assertThat(checks).hasValue(3);
        var secondAttempt = new OperationalReferenceQuery(TENANT, "61", "71", REPOSITORY, WORKFLOW,
                "scope-rev-1", RUN, 2, PIPELINE, DeterministicIds.execution(REPOSITORY, RUN, 2),
                DeterministicIds.incident(DeterministicIds.execution(REPOSITORY, RUN, 2)), "owner-reference-test");
        assertThat(new ValidateOperationalReferences(query -> Permission.AUTHORIZED, lookup).handle(secondAttempt))
                .isEqualTo(Decision.NOT_READY);
    }

    @Test
    void rejectsStoredContradictionsAndCrossedLogicalIds() {
        publishFailedSnapshot();
        var handler = new ValidateOperationalReferences(query -> Permission.AUTHORIZED, lookup);
        jdbc.update("UPDATE ops.pipeline_executions SET github_run_id = '81002' WHERE id = ?", EXECUTION);
        assertThat(handler.handle(query())).isEqualTo(Decision.MISMATCH);
        jdbc.update("UPDATE ops.pipeline_executions SET github_run_id = ? WHERE id = ?", RUN, EXECUTION);
        var crossed = new OperationalReferenceQuery(TENANT, "61", "71", REPOSITORY, WORKFLOW,
                "scope-rev-1", RUN, 1, PIPELINE, EXECUTION, UUID.randomUUID(), "owner-reference-test");
        assertThat(handler.handle(crossed)).isEqualTo(Decision.MISMATCH);
    }

    @Test
    void neverLooksUpReferencesWithoutCurrentExactScopeAuthorization() {
        var reads = new AtomicInteger();
        OperationalReferenceLookup forbidden = query -> {
            reads.incrementAndGet();
            return Membership.MATCH;
        };
        for (var permission : List.of(Permission.DENIED, Permission.UNAVAILABLE)) {
            var expected = permission == Permission.DENIED ? Decision.DENIED : Decision.UNAVAILABLE;
            assertThat(new ValidateOperationalReferences(query -> permission, forbidden).handle(query())).isEqualTo(expected);
        }
        assertThat(new ValidateOperationalReferences(query -> null, forbidden).handle(query())).isEqualTo(Decision.UNAVAILABLE);
        assertThat(new ValidateOperationalReferences(query -> { throw new IllegalStateException("private upstream body"); }, forbidden)
                .handle(query())).isEqualTo(Decision.UNAVAILABLE);
        assertThat(reads).hasValue(0);
        assertThat(query().toString()).doesNotContain(TENANT.toString(), "scope-rev-1", REPOSITORY);
    }

    @Test
    void deniesChangedCaptureBindingEvenWhenOwnedRecordsExist() {
        publishFailedSnapshot();
        var handler = new ValidateOperationalReferences(query -> query().equals(query) ? Permission.AUTHORIZED : Permission.DENIED, lookup);
        assertThat(handler.handle(query())).isEqualTo(Decision.AUTHORIZED);
        var changed = new OperationalReferenceQuery(TENANT, "61", "71", REPOSITORY, WORKFLOW,
                "scope-rev-2", RUN, 1, PIPELINE, EXECUTION, INCIDENT, "owner-reference-test");
        assertThat(handler.handle(changed)).isEqualTo(Decision.DENIED);
    }

    private static OperationalReferenceQuery query() {
        return new OperationalReferenceQuery(TENANT, "61", "71", REPOSITORY, WORKFLOW,
                "scope-rev-1", RUN, 1, PIPELINE, EXECUTION, INCIDENT, "owner-reference-test");
    }

    private void publishFailedSnapshot() {
        var snapshot = new ExecutionSnapshot("1.0", UUID.randomUUID(), "execution.snapshot", "github_actions", NOW, NOW,
                "owner-reference-test", new ExecutionSnapshot.Pipeline(REPOSITORY, WORKFLOW),
                new ExecutionSnapshot.Execution(RUN, 1, "main", "completed", "failure", NOW, NOW),
                new ExecutionSnapshot.Commit("0123456789abcdef0123456789abcdef01234567"), null, null,
                List.of(new ExecutionSnapshot.Stage(PipelineStage.TESTING, "completed", NOW, NOW)),
                new ExecutionSnapshot.Failure(PipelineStage.TESTING, "Protected failure", NOW));
        ApplyExecutionSnapshotCommand command = new ExecutionSnapshotTransformer()
                .fromJson(JsonMapper.builder().build().writeValueAsString(snapshot));
        assertThat(snapshots.handle(command)).isEqualTo("APPLIED");
    }
}
