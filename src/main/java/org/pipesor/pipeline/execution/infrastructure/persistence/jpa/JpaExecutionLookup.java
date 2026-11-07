package org.pipesor.pipeline.execution.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.pipesor.pipeline.execution.application.ports.ExecutionLookup;
import org.pipesor.pipeline.execution.application.queries.ExecutionStageView;
import org.pipesor.pipeline.execution.application.queries.ExecutionView;
import org.pipesor.pipeline.execution.domain.model.valueobjects.ExecutionOutcome;
import org.pipesor.pipeline.incident.application.ports.IncidentOriginLookup;
import org.pipesor.pipeline.incident.application.ports.VerificationLookup;
import org.pipesor.pipeline.incident.domain.model.valueobjects.VerificationExecution;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaExecutionLookup implements ExecutionLookup, IncidentOriginLookup, VerificationLookup {
    private final EntityManager em;

    public JpaExecutionLookup(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<ExecutionView> findById(UUID id) {
        return Optional.ofNullable(em.find(ExecutionJpaEntity.class, id)).map(this::view);
    }

    @Override
    public List<ExecutionView> list(UUID pipelineId, int offset, int limit) {
        var query = em.createQuery("select e from ExecutionJpaEntity e" + (pipelineId == null ? "" : " where e.pipelineId = :parent") + " order by e.createdAt, e.id", ExecutionJpaEntity.class);
        if (pipelineId != null) {
            query.setParameter("parent", pipelineId);
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultStream().map(this::view).toList();
    }

    private ExecutionView view(ExecutionJpaEntity entity) {
        return new ExecutionView(entity.getId(), entity.pipelineId, entity.githubRunId, entity.runAttempt, entity.branch, entity.commitSha, entity.externalStatus, entity.externalConclusion, entity.failed, entity.startedAt, entity.completedAt, entity.correlationId, entity.getCreatedAt());
    }

    @Override
    public List<ExecutionStageView> stages(UUID executionId) {
        return em.createQuery("select s from ExecutionStageJpaEntity s where s.executionId = :id order by s.createdAt, s.id", ExecutionStageJpaEntity.class)
                .setParameter("id", executionId).getResultStream().map(entity -> new ExecutionStageView(entity.getId(), entity.executionId, entity.stage, entity.externalStatus, entity.startedAt, entity.completedAt, entity.correlationId, entity.getCreatedAt())).toList();
    }

    @Override
    @Transactional
    public Optional<ExecutionOutcome> lockExecution(UUID id) {
        return Optional.ofNullable(em.find(ExecutionJpaEntity.class, id, LockModeType.PESSIMISTIC_READ))
                .map(entity -> new ExecutionOutcome(entity.getId(), entity.pipelineId, entity.failed));
    }
    @Override
    @Transactional
    public Optional<VerificationExecution> lockVerification(UUID id) {
        return Optional.ofNullable(em.find(ExecutionJpaEntity.class, id, LockModeType.PESSIMISTIC_READ))
                .map(e -> new VerificationExecution(
                        e.getId(), e.pipelineId, e.startedAt, e.externalStatus, e.externalConclusion, e.failed));
    }

    @Override
    public Map<UUID, UUID> pipelineIds(Set<UUID> executionIds) {
        if (executionIds.isEmpty()) { return Map.of(); }
        return em.createQuery("select e.id, e.pipelineId from ExecutionJpaEntity e where e.id in :ids", Object[].class)
                .setParameter("ids", executionIds).getResultStream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (UUID) row[1]));
    }

}
