package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.commands.RecordDiagnosisSummaryCommand;
import org.pipesor.pipeline.incident.application.ports.DiagnosisSummaryStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code ops.incident_diagnoses}: one monotonic upsert per summary (ADR-027). */
@Repository
public class SqlDiagnosisSummaryStore implements DiagnosisSummaryStore {
    private final NamedParameterJdbcTemplate jdbc;

    public SqlDiagnosisSummaryStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Target> target(UUID incidentId) {
        return jdbc.query("""
                SELECT i.id, i.pipeline_id, r.github_repository_id,
                       (SELECT ir.execution_id FROM ops.incident_runs ir
                        WHERE ir.incident_id = i.id AND ir.role = 'ORIGIN') AS origin_execution_id
                FROM ops.incidents i
                JOIN ops.pipelines p ON p.id = i.pipeline_id
                JOIN ops.repositories r ON r.id = p.repository_id
                WHERE i.id = :id
                """, new MapSqlParameterSource("id", incidentId), (row, index) -> new Target(row.getObject("id", UUID.class),
                row.getObject("pipeline_id", UUID.class), row.getString("github_repository_id"),
                row.getObject("origin_execution_id", UUID.class))).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean apply(RecordDiagnosisSummaryCommand command, Instant projectedAt) {
        var parameters = new MapSqlParameterSource()
                .addValue("incidentId", command.incidentId())
                .addValue("diagnosisId", command.diagnosisId())
                .addValue("version", command.version())
                .addValue("executionId", command.executionId())
                .addValue("status", command.status().name())
                .addValue("reviewRevision", command.reviewRevision())
                .addValue("reviewedAt", time(command.reviewedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("probableCause", command.probableCause(), Types.VARCHAR)
                .addValue("createdAt", time(command.createdAt()))
                .addValue("correlationId", command.correlationId())
                .addValue("projectedAt", time(projectedAt));
        // Only a later (version, reviewRevision) replaces the stored summary: replays and late deliveries are no-ops.
        return jdbc.update("""
                INSERT INTO ops.incident_diagnoses AS d (incident_id, diagnosis_id, diagnosis_version, execution_id, status,
                    review_revision, reviewed_at, probable_cause, diagnosis_created_at, correlation_id, projected_at)
                VALUES (:incidentId, :diagnosisId, :version, :executionId, :status, :reviewRevision, :reviewedAt,
                    :probableCause, :createdAt, :correlationId, :projectedAt)
                ON CONFLICT (incident_id) DO UPDATE SET diagnosis_id = EXCLUDED.diagnosis_id,
                    diagnosis_version = EXCLUDED.diagnosis_version, execution_id = EXCLUDED.execution_id,
                    status = EXCLUDED.status, review_revision = EXCLUDED.review_revision, reviewed_at = EXCLUDED.reviewed_at,
                    probable_cause = EXCLUDED.probable_cause, diagnosis_created_at = EXCLUDED.diagnosis_created_at,
                    correlation_id = EXCLUDED.correlation_id, projected_at = EXCLUDED.projected_at
                WHERE (EXCLUDED.diagnosis_version, EXCLUDED.review_revision) > (d.diagnosis_version, d.review_revision)
                """, parameters) == 1;
    }

    private static OffsetDateTime time(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
