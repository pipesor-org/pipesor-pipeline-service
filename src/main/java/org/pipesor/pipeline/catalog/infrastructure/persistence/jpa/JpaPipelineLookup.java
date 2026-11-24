package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.ports.PipelineSettingsStore;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaPipelineLookup implements PipelineLookup, PipelineSettingsStore {
    private final EntityManager em;

    public JpaPipelineLookup(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<PipelineView> findById(UUID id) {
        return Optional.ofNullable(em.find(PipelineJpaEntity.class, id)).map(this::view);
    }

    @Override
    public List<PipelineView> list(UUID repositoryId, int offset, int limit) {
        var query = em.createQuery("select e from PipelineJpaEntity e" + (repositoryId == null ? "" : " where e.repositoryId = :parent") + " order by e.createdAt, e.id", PipelineJpaEntity.class);
        if (repositoryId != null) {
            query.setParameter("parent", repositoryId);
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultStream().map(this::view).toList();
    }

    private PipelineView view(PipelineJpaEntity entity) {
        var repository = em.find(GitHubRepositoryJpaEntity.class, entity.repositoryId);
        return view(entity, repository);
    }

    private PipelineView view(PipelineJpaEntity entity, GitHubRepositoryJpaEntity repository) {
        return new PipelineView(entity.getId(), entity.repositoryId, repository.getGithubRepositoryId(), repository.getFullName(), repository.getDefaultBranch(), entity.githubWorkflowId, entity.workflowName, entity.workflowFile, entity.environment, entity.criticality, entity.monitoringStatus, entity.responsibleTeamRef, entity.responsibleTeamNameSnapshot, entity.getCreatedAt(), Set.of());
    }
    @Override
    @Transactional
    public PipelineView update(ConfigurePipelineCommand command) {
        var row = em.find(PipelineJpaEntity.class, command.pipelineId(), LockModeType.PESSIMISTIC_WRITE);
        if (row == null) { throw new NoSuchElementException("Pipeline not found"); }
        if (command.section() != ConfigurePipelineCommand.Section.OWNERSHIP) {
            row.criticality = command.criticality();
            row.monitoringStatus = command.monitoringStatus();
            row.environment = command.environment();
        }
        if (command.section() != ConfigurePipelineCommand.Section.POLICY) {
            row.responsibleTeamRef = command.responsibleTeamRef();
            row.responsibleTeamNameSnapshot = command.responsibleTeamNameSnapshot();
        }
        em.flush();
        return view(row);
    }

    @Override
    @Transactional
    public PipelineView lock(UUID id) {
        var row = em.find(PipelineJpaEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) { throw new NoSuchElementException("Pipeline not found"); }
        return view(row);
    }

    @Override
    public List<PipelineView> listVisible(String actorRef, Set<String> allowedRepositoryIds, int offset, int limit) {
        if (allowedRepositoryIds.isEmpty()) { return List.of(); }
        return em.createQuery("""
                select p, r from PipelineJpaEntity p, GitHubRepositoryJpaEntity r
                where p.repositoryId = r.id and r.githubRepositoryId in :allowed and exists
                (select g.id from PipelineAccessGrantJpaEntity g where g.pipelineId = p.id and g.principalRef = :actor)
                order by p.createdAt, p.id
                """, Object[].class).setParameter("actor", actorRef).setParameter("allowed", allowedRepositoryIds)
                .setFirstResult(offset).setMaxResults(limit).getResultStream()
                .map(row -> view((PipelineJpaEntity) row[0], (GitHubRepositoryJpaEntity) row[1])).toList();
    }

    @Override
    public List<Registration> registrations(String githubRepositoryId) {
        return em.createQuery("""
                select p.id, p.githubWorkflowId from PipelineJpaEntity p, GitHubRepositoryJpaEntity r
                where p.repositoryId = r.id and r.githubRepositoryId = :repository
                order by length(p.githubWorkflowId), p.githubWorkflowId
                """, Object[].class).setParameter("repository", githubRepositoryId).getResultStream()
                .map(row -> new Registration((UUID) row[0], (String) row[1])).toList();
    }
    @Override
    public Set<String> candidateRepositoryIds(String actorRef) {
        return Set.copyOf(em.createQuery("""
                select distinct r.githubRepositoryId from PipelineJpaEntity p, GitHubRepositoryJpaEntity r
                where p.repositoryId = r.id and exists
                (select g.id from PipelineAccessGrantJpaEntity g where g.pipelineId = p.id and g.principalRef = :actor)
                """, String.class).setParameter("actor", actorRef).getResultList());
    }
}
