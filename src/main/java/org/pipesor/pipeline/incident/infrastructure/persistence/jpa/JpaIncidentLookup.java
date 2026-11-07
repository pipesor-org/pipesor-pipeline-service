package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaIncidentLookup implements IncidentLookup {
    private final EntityManager em;

    public JpaIncidentLookup(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<IncidentView> findById(UUID id) {
        return Optional.ofNullable(em.find(IncidentJpaEntity.class, id)).map(this::view);
    }

    @Override
    public List<IncidentView> list(UUID pipelineId, int offset, int limit) {
        var query = em.createQuery("select e from IncidentJpaEntity e" + (pipelineId == null ? "" : " where e.pipelineId = :parent") + " order by e.createdAt, e.id", IncidentJpaEntity.class);
        if (pipelineId != null) {
            query.setParameter("parent", pipelineId);
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultStream().map(this::view).toList();
    }

    private IncidentView view(IncidentJpaEntity entity) {
        return new IncidentView(entity.getId(), entity.pipelineId, entity.status, entity.severity, entity.stage, entity.summary, entity.assigneeRef, entity.assigneeDisplayNameSnapshot, entity.detectedAt, entity.remediationStartedAt, entity.verificationStartedAt, entity.resolvedAt, entity.correlationId, entity.getCreatedAt());
    }

    @Override
    public List<IncidentRunView> runs(UUID incidentId, int offset, int limit) {
        return em.createQuery("select r from IncidentRunJpaEntity r where r.incidentId = :id order by r.createdAt, r.id", IncidentRunJpaEntity.class)
                .setParameter("id", incidentId).setFirstResult(offset).setMaxResults(limit).getResultStream()
                .map(entity -> new IncidentRunView(entity.getId(), entity.incidentId, entity.executionId, entity.role, entity.correlationId, entity.getCreatedAt())).toList();
    }
}
