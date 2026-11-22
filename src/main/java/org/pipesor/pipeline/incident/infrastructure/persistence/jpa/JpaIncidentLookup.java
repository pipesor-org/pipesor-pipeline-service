package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentRunRole;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
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
    @SuppressWarnings("unchecked")
    public List<IncidentView> queue(String actorRef, Set<String> githubRepositoryIds, Set<IncidentStatus> statuses,
                                    ListIncidentQueueQuery.Cursor after, int limit) {
        if (githubRepositoryIds.isEmpty() || statuses.isEmpty()) { return List.of(); }
        var query = em.createNativeQuery("""
                SELECT i.* FROM ops.incidents i
                JOIN ops.pipelines p ON p.id = i.pipeline_id
                JOIN ops.repositories r ON r.id = p.repository_id
                WHERE r.github_repository_id IN (:repositories)
                  AND i.status::text IN (:statuses)
                  AND EXISTS (SELECT 1 FROM ops.pipeline_access_grants g
                              WHERE g.pipeline_id = p.id AND g.principal_ref = :actor)
                """ + (after == null ? "" : "  AND (i.created_at, i.id) < (:createdAt, :id)\n") + """
                ORDER BY i.created_at DESC, i.id DESC
                LIMIT :limit
                """, IncidentJpaEntity.class)
                .setParameter("repositories", githubRepositoryIds)
                .setParameter("statuses", statuses.stream().map(Enum::name).toList())
                .setParameter("actor", actorRef)
                .setParameter("limit", limit);
        if (after != null) {
            query.setParameter("createdAt", after.createdAt()).setParameter("id", after.id());
        }
        return ((List<IncidentJpaEntity>) query.getResultList()).stream().map(this::view).toList();
    }

    @Override
    public Optional<UUID> originExecution(UUID incidentId) {
        return em.createQuery("""
                select r.executionId from IncidentRunJpaEntity r
                where r.incidentId = :id and r.role = :role
                """, UUID.class).setParameter("id", incidentId).setParameter("role", IncidentRunRole.ORIGIN)
                .getResultStream().findFirst();
    }

    @Override
    public List<IncidentRunView> runs(UUID incidentId, int offset, int limit) {
        return em.createQuery("select r from IncidentRunJpaEntity r where r.incidentId = :id order by r.createdAt, r.id", IncidentRunJpaEntity.class)
                .setParameter("id", incidentId).setFirstResult(offset).setMaxResults(limit).getResultStream()
                .map(entity -> new IncidentRunView(entity.getId(), entity.incidentId, entity.executionId, entity.role, entity.correlationId, entity.getCreatedAt())).toList();
    }
}
