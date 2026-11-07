package org.pipesor.pipeline;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.application.services.SnapshotRejected;
import org.pipesor.pipeline.execution.interfaces.messaging.ServiceBusSnapshotConsumer;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionSnapshotTests extends PostgresIntegrationTest {
    // Independently computed with Python uuid.uuid5(NAMESPACE_URL, the approved canonical names).
    private static final UUID PIPELINE = UUID.fromString("46f06e9c-4096-592c-bfa0-fa8ae2fc0671");
    private static final UUID EXECUTION = UUID.fromString("cdaf6bbd-dae0-5750-93ce-8e1c1fd6787b");
    private static final UUID INCIDENT = UUID.fromString("9e208db4-51f9-5b9e-98ad-fd9275d4ae90");
    private static final Instant BASE = Instant.parse("2026-11-06T12:00:00.123456Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionSnapshotService service;
    @Autowired PlatformTransactionManager manager;
    private final ExecutionSnapshotTransformer transform = new ExecutionSnapshotTransformer();
    private UUID repository;

    @BeforeEach
    void seedRegistration() {
        repository = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, '9001', 'snapshot/service', now())", repository);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                        criticality, monitoring_status, created_at)
                VALUES (?, ?, '7001', 'Snapshot workflow', 'workflow.yml', 'HIGH', 'ACTIVE', now())
                """, PIPELINE, repository);
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM ops.execution_event_receipts WHERE correlation_id = 'snapshot-test'");
            jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id = ?)", PIPELINE);
            jdbc.update("DELETE FROM ops.incidents WHERE pipeline_id = ?", PIPELINE);
            jdbc.update("DELETE FROM ops.execution_stages WHERE execution_id IN (SELECT id FROM ops.pipeline_executions WHERE pipeline_id = ?)", PIPELINE);
            jdbc.update("DELETE FROM ops.pipeline_executions WHERE pipeline_id = ?", PIPELINE);
            jdbc.update("DELETE FROM ops.pipelines WHERE repository_id = ?", repository);
            jdbc.update("DELETE FROM ops.repositories WHERE id = ?", repository);
        });
    }

    @Test
    void persistsSnapshotsWithStableIdsAndRejectsRegressionDespiteRedelivery() {
        assertThat(apply(event(UUID.randomUUID(), BASE, "queued", null, 1))).isEqualTo("APPLIED");
        assertThat(apply(event(UUID.randomUUID(), BASE.plusSeconds(1), "in_progress", null, 1))).isEqualTo("APPLIED");
        var completed = event(UUID.randomUUID(), BASE.plusSeconds(2), "completed", "failure", 1);
        assertThat(apply(completed)).isEqualTo("APPLIED");
        assertThat(apply(completed)).isEqualTo("DUPLICATE");
        assertThat(apply(event(UUID.randomUUID(), BASE.plusSeconds(2), "completed", "failure", 1))).isEqualTo("DUPLICATE");
        assertThat(apply(event(UUID.randomUUID(), BASE.plusSeconds(3), "completed", "failure", 1))).isEqualTo("APPLIED");
        assertThat(apply(event(UUID.randomUUID(), BASE.plusSeconds(1), "queued", null, 1))).isEqualTo("IGNORED_STALE");
        assertThat(jdbc.queryForObject("SELECT id FROM ops.pipeline_executions WHERE pipeline_id = ?", UUID.class, PIPELINE)).isEqualTo(EXECUTION);
        assertThat(jdbc.queryForObject("SELECT external_status FROM ops.pipeline_executions WHERE id = ?", String.class, EXECUTION)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT branch FROM ops.pipeline_executions WHERE id = ?", String.class, EXECUTION)).isEqualTo("release/snapshot");
        assertThat(jdbc.queryForObject("SELECT started_at FROM ops.pipeline_executions WHERE id = ?", Timestamp.class, EXECUTION).toInstant()).isEqualTo(BASE);
        assertThat(jdbc.queryForObject("SELECT id FROM ops.incidents WHERE pipeline_id = ?", UUID.class, PIPELINE)).isEqualTo(INCIDENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incident_runs WHERE incident_id = ? AND role = 'ORIGIN'", Integer.class, INCIDENT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT external_status FROM ops.execution_stages WHERE execution_id = ?", String.class, EXECUTION)).isEqualTo("completed");
    }

    @Test
    void detectsMessageAndVersionConflictsAndPreservesTerminalResults() {
        UUID id = UUID.randomUUID();
        apply(event(id, BASE.plusSeconds(2), "completed", "failure", 1));
        assertRejected("EVENT_ID_CONFLICT", event(id, BASE.plusSeconds(2), "completed", "success", 1));
        assertRejected("SNAPSHOT_VERSION_CONFLICT", event(UUID.randomUUID(), BASE.plusSeconds(2), "completed", "success", 1));
        assertRejected("TERMINAL_RESULT_CONFLICT", event(UUID.randomUUID(), BASE.plusSeconds(3), "in_progress", null, 1));
        assertRejected("TERMINAL_RESULT_CONFLICT", event(UUID.randomUUID(), BASE.plusSeconds(4), "completed", "success", 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.execution_event_receipts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT external_conclusion FROM ops.pipeline_executions WHERE id = ?", String.class, EXECUTION)).isEqualTo("failure");
    }

    @Test
    void ordersSubmicrosecondVersionsAndTreatsAttemptsAsIndependentIdentities() {
        apply(event(UUID.randomUUID(), BASE.plusNanos(100), "queued", null, 1));
        apply(event(UUID.randomUUID(), BASE.plusNanos(900), "in_progress", null, 1));
        assertThat(apply(event(UUID.randomUUID(), BASE.plusNanos(500), "queued", null, 1))).isEqualTo("IGNORED_STALE");
        apply(event(UUID.randomUUID(), BASE.plusSeconds(2), "completed", "failure", 1));
        apply(event(UUID.randomUUID(), BASE.plusSeconds(3), "completed", "failure", 2));
        assertThat(jdbc.queryForList("SELECT id FROM ops.incidents WHERE pipeline_id = ?", UUID.class, PIPELINE))
                .containsExactlyInAnyOrder(INCIDENT, UUID.fromString("54288770-71af-5de6-8d65-b080df225078"));
    }

    @Test
    void pausedWorkflowIsAcknowledgedWithoutWritesAndUnknownWorkflowIsPermanent() {
        jdbc.update("UPDATE ops.pipelines SET monitoring_status = 'PAUSED' WHERE id = ?", PIPELINE);
        var paused = event(UUID.randomUUID(), BASE, "completed", "failure", 1);
        assertThat(apply(paused)).isEqualTo("IGNORED_PAUSED");
        jdbc.update("UPDATE ops.pipelines SET monitoring_status = 'ACTIVE' WHERE id = ?", PIPELINE);
        assertThat(apply(paused)).isEqualTo("DUPLICATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_executions WHERE pipeline_id = ?", Integer.class, PIPELINE)).isZero();
        var unknown = JSON.writeValueAsString(event(UUID.randomUUID(), BASE, "completed", "failure", 1)).replace("7001", "7002");
        assertThat(assertThrows(SnapshotRejected.class, () -> service.handle(transform.fromJson(unknown))).getMessage()).isEqualTo("UNKNOWN_WORKFLOW");
    }

    @Test
    void concurrentDeliveriesCommitOneIncidentAndOneOrigin() throws Exception {
        var command = transform.fromJson(JSON.writeValueAsString(event(UUID.randomUUID(), BASE, "completed", "failure", 1)));
        var ready = new CountDownLatch(2);
        Callable<String> delivery = () -> {
            ready.countDown();
            if (!ready.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Missing concurrent delivery"); }
            return service.handle(command);
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(delivery); var second = workers.submit(delivery);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("APPLIED", "DUPLICATE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incidents WHERE pipeline_id = ?", Integer.class, PIPELINE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.execution_event_receipts", Integer.class)).isEqualTo(1);
    }

    @Test
    void consumerSettlesAfterCommitAndRetriesWithoutLeavingPartialState() {
        var consumer = new ServiceBusSnapshotConsumer(service, 262144);
        var context = context(JSON.writeValueAsString(event(UUID.randomUUID(), BASE, "completed", "failure", 1)));
        jdbc.execute("""
                CREATE FUNCTION ops.test_snapshot_reject() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced origin rejection' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_snapshot_reject BEFORE INSERT ON ops.incident_runs FOR EACH ROW EXECUTE FUNCTION ops.test_snapshot_reject()");
        try {
            consumer.accept(context);
            verify(context).abandon(); verify(context, never()).complete();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_executions WHERE pipeline_id = ?", Integer.class, PIPELINE)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.execution_event_receipts", Integer.class)).isZero();
            var exhausted = context(JSON.writeValueAsString(event(UUID.randomUUID(), BASE, "completed", "failure", 1)));
            when(exhausted.getMessage().getDeliveryCount()).thenReturn(10L);
            consumer.accept(exhausted);
            verify(exhausted).deadLetter(org.mockito.ArgumentMatchers.argThat(options -> "RETRY_EXHAUSTED".equals(options.getDeadLetterReason())));
            verify(exhausted, never()).abandon();
        } finally {
            jdbc.execute("DROP TRIGGER test_snapshot_reject ON ops.incident_runs");
            jdbc.execute("DROP FUNCTION ops.test_snapshot_reject()");
        }
        doAnswer(call -> {
            assertThat(jdbc.queryForObject("SELECT id FROM ops.incidents WHERE pipeline_id = ?", UUID.class, PIPELINE)).isEqualTo(INCIDENT);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(context).complete();
        consumer.accept(context); verify(context).complete();
        var malformed = context("{malformed"); consumer.accept(malformed);
        verify(malformed).deadLetter(any()); verify(malformed, never()).complete();
    }

    @Test
    void rejectsAValidlyShapedEventThatAssignsAnExistingRunToAnotherWorkflow() {
        apply(event(UUID.randomUUID(), BASE, "in_progress", null, 1));
        UUID otherPipeline = DeterministicIds.pipeline("9001", "7002");
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                    criticality, monitoring_status, created_at)
                VALUES (?, ?, '7002', 'Other workflow', 'other.yml', 'LOW', 'ACTIVE', now())
                """, otherPipeline, repository);
        var payload = JSON.writeValueAsString(event(UUID.randomUUID(), BASE.plusSeconds(1), "completed", "success", 1)).replace("7001", "7002");
        assertThat(assertThrows(SnapshotRejected.class, () -> service.handle(transform.fromJson(payload))).getMessage())
                .isEqualTo("EXECUTION_IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT pipeline_id FROM ops.pipeline_executions WHERE id = ?", UUID.class, EXECUTION)).isEqualTo(PIPELINE);
        assertThat(jdbc.queryForObject("SELECT external_status FROM ops.pipeline_executions WHERE id = ?", String.class, EXECUTION)).isEqualTo("in_progress");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.execution_event_receipts", Integer.class)).isEqualTo(1);
    }

    private ServiceBusReceivedMessageContext context(String json) {
        var message = mock(ServiceBusReceivedMessage.class);
        var context = mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        when(message.getBody()).thenReturn(BinaryData.fromString(json));
        when(message.getDeliveryCount()).thenReturn(1L);
        return context;
    }

    private String apply(ExecutionSnapshot snapshot) { return service.handle(transform.fromJson(JSON.writeValueAsString(snapshot))); }
    private void assertRejected(String reason, ExecutionSnapshot snapshot) {
        assertThat(assertThrows(SnapshotRejected.class, () -> apply(snapshot)).getMessage()).isEqualTo(reason);
    }

    private ExecutionSnapshot event(UUID id, Instant updated, String status, String conclusion, int attempt) {
        boolean failed = "failure".equals(conclusion) || "timed_out".equals(conclusion);
        return new ExecutionSnapshot("1.0", id, "execution.snapshot", "github_actions", updated.plusSeconds(1), updated,
                "snapshot-test", new ExecutionSnapshot.Pipeline("9001", "7001"),
                new ExecutionSnapshot.Execution("8001", attempt, "release/snapshot", status, conclusion,
                        BASE, "completed".equals(status) ? BASE.plusSeconds(2) : null),
                new ExecutionSnapshot.Commit("0123456789abcdef0123456789abcdef01234567"), null, null,
                List.of(new ExecutionSnapshot.Stage(PipelineStage.TESTING, status, BASE, null)),
                failed ? new ExecutionSnapshot.Failure(PipelineStage.TESTING, "Sanitized snapshot failure", BASE.plusSeconds(2)) : null);
    }
}
