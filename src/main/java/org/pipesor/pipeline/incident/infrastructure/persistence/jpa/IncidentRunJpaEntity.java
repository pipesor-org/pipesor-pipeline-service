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
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentRunRole;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "incident_runs", schema = "ops")
public class IncidentRunJpaEntity extends CreatedJpaEntity {

    @Column(nullable = false)
    UUID incidentId;

    @Column(nullable = false)
    UUID executionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"IncidentRunRole\"")
    IncidentRunRole role;

    @Column(nullable = false, columnDefinition = "text")
    String correlationId;

    protected IncidentRunJpaEntity() {
    }

    IncidentRunJpaEntity(UUID id, Incident incident) {
        super(id, incident.getDetails().createdAt());
        incidentId = incident.getId();
        executionId = incident.getOrigin().executionId();
        role = IncidentRunRole.ORIGIN;
        correlationId = incident.getDetails().correlationId();
    }
    IncidentRunJpaEntity(UUID id, UUID incidentId, UUID executionId, String correlationId, Instant now) {
        super(id, now);
        this.incidentId = incidentId;
        this.executionId = executionId;
        role = IncidentRunRole.VERIFICATION;
        this.correlationId = correlationId;
    }

}
