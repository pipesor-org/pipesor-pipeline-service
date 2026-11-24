package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.DiagnosisSummary;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.RepositoryRef;
import org.pipesor.pipeline.incident.application.queries.WorkflowRef;
import org.pipesor.pipeline.incident.domain.model.valueobjects.DiagnosisStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.Severity;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

/** SQL projection of an incident with its repository, workflow and projected diagnosis summary (ADR-027). */
final class IncidentRows {
    static final String FROM = """
            FROM ops.incidents i
            JOIN ops.pipelines p ON p.id = i.pipeline_id
            JOIN ops.repositories r ON r.id = p.repository_id
            LEFT JOIN ops.incident_diagnoses d ON d.incident_id = i.id
            """;
    static final String SELECT = """
            SELECT i.id, i.pipeline_id, i.status::text AS status, i.severity::text AS severity, i.stage::text AS stage,
                   i.summary, i.assignee_ref, i.assignee_display_name_snapshot, i.detected_at, i.remediation_started_at,
                   i.verification_started_at, i.resolved_at, i.correlation_id, i.created_at, i.display_number,
                   r.github_repository_id, r.full_name, p.github_workflow_id, p.workflow_name,
                   d.status AS diagnosis_status, d.probable_cause AS diagnosis_probable_cause, d.diagnosis_created_at
            """ + FROM;

    private IncidentRows() {}

    static IncidentView view(ResultSet row, int index) throws SQLException {
        var severity = row.getString("severity");
        var diagnosisStatus = row.getString("diagnosis_status");
        return new IncidentView(row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class),
                IncidentStatus.valueOf(row.getString("status")), severity == null ? null : Severity.valueOf(severity),
                PipelineStage.valueOf(row.getString("stage")), row.getString("summary"), row.getString("assignee_ref"),
                row.getString("assignee_display_name_snapshot"), instant(row, "detected_at"),
                instant(row, "remediation_started_at"), instant(row, "verification_started_at"), instant(row, "resolved_at"),
                row.getString("correlation_id"), instant(row, "created_at"), row.getLong("display_number"),
                repository(row), workflow(row),
                diagnosisStatus == null ? null : new DiagnosisSummary(DiagnosisStatus.valueOf(diagnosisStatus),
                        row.getString("diagnosis_probable_cause"), instant(row, "diagnosis_created_at")));
    }

    static RepositoryRef repository(ResultSet row) throws SQLException {
        return new RepositoryRef(row.getString("github_repository_id"), row.getString("full_name"));
    }

    static WorkflowRef workflow(ResultSet row) throws SQLException {
        return new WorkflowRef(row.getString("github_workflow_id"), row.getString("workflow_name"));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
