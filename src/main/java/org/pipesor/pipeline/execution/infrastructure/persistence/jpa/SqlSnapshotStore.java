package org.pipesor.pipeline.execution.infrastructure.persistence.jpa;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;
import org.pipesor.pipeline.execution.application.ports.SnapshotStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SqlSnapshotStore implements SnapshotStore {
    private final JdbcTemplate jdbc;
    public SqlSnapshotStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void lockEvent(UUID id) {
        lockKey("event:" + id);
    }

    private void lockKey(String key) {
        // Distinct logical lock namespaces also cover rows that do not exist yet.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (row, index) -> 0, key);
    }

    @Override
    public Optional<String> receiptDigest(UUID id) {
        return jdbc.queryForList("SELECT message_digest FROM ops.execution_event_receipts WHERE event_id = ?", String.class, id).stream().findFirst();
    }

    @Override
    public Optional<Registration> lockPipeline(String repository, String workflow) {
        // MVP serialization per workflow also protects the first observation, before an execution row exists.
        return jdbc.query("""
                SELECT p.id, p.monitoring_status::text = 'ACTIVE' AS active
                FROM ops.pipelines p JOIN ops.repositories r ON r.id = p.repository_id
                WHERE r.github_repository_id = ? AND p.github_workflow_id = ? FOR UPDATE OF p
                """, (row, index) -> new Registration(row.getObject("id", UUID.class), row.getBoolean("active")), repository, workflow).stream().findFirst();
    }

    @Override
    public Optional<Version> latest(UUID id) {
        return jdbc.query("""
                SELECT source_updated_at, content_digest, source_submicro_nanos FROM ops.execution_event_receipts
                WHERE execution_id = ? AND disposition = 'APPLIED'
                ORDER BY source_updated_at DESC, source_submicro_nanos DESC LIMIT 1
                """, (row, index) -> new Version(row.getTimestamp(1).toInstant().plusNanos(row.getInt(3)), row.getString(2)), id).stream().findFirst();
    }

    @Override
    public Optional<StoredExecution> current(UUID id, UUID pipeline, String run, int attempt) {
        lockKey("execution:" + id);
        return jdbc.query("""
                SELECT id, pipeline_id, github_run_id, run_attempt, external_status, external_conclusion FROM ops.pipeline_executions
                WHERE id = ? OR (pipeline_id = ? AND github_run_id = ? AND run_attempt = ?)
                ORDER BY (id = ?) DESC FOR UPDATE
                """, (row, index) -> new StoredExecution(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3), row.getInt(4), row.getString(5), row.getString(6)), id, pipeline, run, attempt, id).stream().findFirst();
    }

    @Override
    public boolean incidentExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ops.incidents WHERE id = ?)", Boolean.class, id));
    }

    @Override
    public void persist(ExecutionSnapshot event, UUID pipeline, Instant now) {
        var e = event.execution();
        jdbc.update("""
                INSERT INTO ops.pipeline_executions
                  (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha, external_status,
                   external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET branch = EXCLUDED.branch, commit_sha = EXCLUDED.commit_sha,
                  external_status = EXCLUDED.external_status, external_conclusion = EXCLUDED.external_conclusion,
                  failed = EXCLUDED.failed, started_at = EXCLUDED.started_at, completed_at = EXCLUDED.completed_at,
                  correlation_id = EXCLUDED.correlation_id
                """, event.executionId(), pipeline, e.githubRunId(), e.runAttempt(), e.branch(), event.commit().sha(),
                e.status(), e.conclusion(), event.failed(), timestamp(e.startedAt()), timestamp(e.completedAt()), event.correlationId(), Timestamp.from(now));
        for (var stage : event.stages()) {
            jdbc.update("""
                    INSERT INTO ops.execution_stages (id, execution_id, stage, external_status, started_at, completed_at, correlation_id, created_at)
                    VALUES (?, ?, ?::ops."PipelineStage", ?, ?, ?, ?, ?)
                    ON CONFLICT (execution_id, stage) DO UPDATE SET external_status = EXCLUDED.external_status,
                      started_at = EXCLUDED.started_at, completed_at = EXCLUDED.completed_at, correlation_id = EXCLUDED.correlation_id
                    """, UUID.randomUUID(), event.executionId(), stage.stage().name(), stage.status(), timestamp(stage.startedAt()),
                    timestamp(stage.completedAt()), event.correlationId(), Timestamp.from(now));
        }
    }

    @Override
    public void receipt(ApplyExecutionSnapshotCommand command, String disposition, Instant now) {
        var event = command.snapshot();
        jdbc.update("""
                INSERT INTO ops.execution_event_receipts
                  (event_id, execution_id, message_digest, content_digest, source_updated_at, source_submicro_nanos, disposition, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.executionId(), command.messageDigest(), command.contentDigest(),
                Timestamp.from(event.sourceUpdatedAt().truncatedTo(ChronoUnit.MICROS)),
                event.sourceUpdatedAt().getNano() % 1000, disposition, event.correlationId(), Timestamp.from(now));
    }

    private Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
}
