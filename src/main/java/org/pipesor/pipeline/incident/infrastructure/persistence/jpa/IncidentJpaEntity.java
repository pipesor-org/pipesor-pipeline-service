package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.Severity;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "incidents", schema = "ops")
public class IncidentJpaEntity extends CreatedJpaEntity {

    @Column(nullable = false)
    UUID pipelineId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"IncidentStatus\"")
    IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = true, columnDefinition = "ops.\"Severity\"")
    Severity severity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"PipelineStage\"")
    PipelineStage stage;

    @Column(nullable = false, columnDefinition = "text")
    String summary;

    @Column(nullable = true, columnDefinition = "text")
    String assigneeRef;

    @Column(nullable = true, columnDefinition = "text")
    String assigneeDisplayNameSnapshot;

    @Column(nullable = false)
    Instant detectedAt;

    @Column(nullable = true)
    Instant remediationStartedAt;

    @Column(nullable = true)
    Instant verificationStartedAt;

    @Column(nullable = true)
    Instant resolvedAt;

    @Column(nullable = false, columnDefinition = "text")
    String correlationId;

    /** Assigned by the database sequence on insert (V7); presentation only, never an identity. */
    @Column(nullable = false, insertable = false, updatable = false)
    Long displayNumber;

    protected IncidentJpaEntity() {
    }

    IncidentJpaEntity(Incident incident) {
        super(incident.getId(), incident.getDetails().createdAt());
        var details = incident.getDetails();
        pipelineId = incident.getPipelineId();
        status = IncidentStatus.OPEN;
        stage = details.stage();
        summary = details.summary();
        detectedAt = details.detectedAt();
        correlationId = details.correlationId();
    }
}
