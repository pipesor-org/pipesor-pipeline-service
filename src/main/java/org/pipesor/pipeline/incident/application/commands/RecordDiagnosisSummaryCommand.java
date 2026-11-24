package org.pipesor.pipeline.incident.application.commands;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.DiagnosisStatus;

/**
 * Recommendation's current summary of an incident's diagnosis (ADR-027). {@code status} is PENDING exactly when no
 * review exists ({@code reviewRevision} 0, {@code reviewedAt} null). {@code pipelineId} and {@code githubRepositoryId}
 * come from the diagnosis authorization scope and must match the incident.
 */
public record RecordDiagnosisSummaryCommand(UUID incidentId, UUID diagnosisId, int version, UUID executionId,
                                            UUID pipelineId, String githubRepositoryId, DiagnosisStatus status,
                                            int reviewRevision, Instant reviewedAt, String probableCause,
                                            Instant createdAt, String correlationId) {
    public static final int MAX_PROBABLE_CAUSE = 1000;

    public RecordDiagnosisSummaryCommand {
        Objects.requireNonNull(incidentId);
        Objects.requireNonNull(diagnosisId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(pipelineId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(correlationId);
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        if (githubRepositoryId == null || !githubRepositoryId.matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("Invalid githubRepositoryId");
        }
        if (reviewRevision < 0 || (status == DiagnosisStatus.PENDING) != (reviewRevision == 0)
                || (reviewedAt == null) != (reviewRevision == 0)) {
            throw new IllegalArgumentException("Inconsistent review state");
        }
        if (probableCause != null && (probableCause.isBlank()
                || probableCause.codePointCount(0, probableCause.length()) > MAX_PROBABLE_CAUSE)) {
            throw new IllegalArgumentException("Invalid probableCause");
        }
    }

    /** Echo of an applied or ignored summary; {@code applied} is false when a same-or-later one was already stored. */
    public record Result(UUID incidentId, UUID diagnosisId, int version, int reviewRevision, DiagnosisStatus status, boolean applied) {}
}
