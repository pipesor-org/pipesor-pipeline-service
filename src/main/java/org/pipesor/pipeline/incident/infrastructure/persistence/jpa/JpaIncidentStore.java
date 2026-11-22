package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.execution.domain.model.valueobjects.ExecutionOutcome;
import org.pipesor.pipeline.incident.application.ports.IncidentStore;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentDetails;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentLifecycle;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentRunRole;
import org.springframework.stereotype.Repository;

@Repository
public class JpaIncidentStore implements IncidentStore {
    private final EntityManager em;

    public JpaIncidentStore(EntityManager em) {
        this.em = em;
    }

    @Override
    public void save(Incident incident, UUID originRunId) {
        em.persist(new IncidentJpaEntity(incident));
        em.persist(new IncidentRunJpaEntity(originRunId, incident));
        em.flush();
    }
    @Override
    public Optional<Incident> lock(UUID id) {
        var row = em.find(IncidentJpaEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) { return Optional.empty(); }
        var origin = em.createQuery("select r.executionId from IncidentRunJpaEntity r where r.incidentId = :id and r.role = :role", UUID.class)
                .setParameter("id", id).setParameter("role", IncidentRunRole.ORIGIN)
                .getSingleResult();
        var ids = new HashSet<>(em.createQuery("select r.executionId from IncidentRunJpaEntity r where r.incidentId = :id and r.role = :role", UUID.class)
                .setParameter("id", id).setParameter("role", IncidentRunRole.VERIFICATION)
                .getResultList());
        var details = new IncidentDetails(
                row.stage, row.summary, row.detectedAt, row.correlationId, row.getCreatedAt(), row.severity);
        var state = new IncidentLifecycle(
                row.status, row.remediationStartedAt, row.verificationStartedAt, row.resolvedAt, ids);
        return Optional.of(Incident.restore(id, row.pipelineId,
                new ExecutionOutcome(origin, row.pipelineId, true), details, state));
    }

    @Override
    public void update(Incident incident, UUID verificationId, UUID linkId, String correlationId, Instant now) {
        var row = em.find(IncidentJpaEntity.class, incident.getId());
        var state = incident.getLifecycle();
        row.status = state.status();
        row.remediationStartedAt = state.remediationStartedAt();
        row.verificationStartedAt = state.verificationStartedAt();
        row.resolvedAt = state.resolvedAt();
        if (verificationId != null) {
            em.persist(new IncidentRunJpaEntity(linkId, incident.getId(), verificationId, correlationId, now));
        }
        em.flush();
    }

}
