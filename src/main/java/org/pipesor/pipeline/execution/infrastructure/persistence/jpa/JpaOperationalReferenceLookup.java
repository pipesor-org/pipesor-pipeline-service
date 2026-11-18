package org.pipesor.pipeline.execution.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup.Membership;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup;
import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaOperationalReferenceLookup implements OperationalReferenceLookup {
    private final EntityManager em;

    public JpaOperationalReferenceLookup(EntityManager em) { this.em = em; }

    @Override
    public Membership check(OperationalReferenceQuery query) {
        // One statement observes committed membership in one PostgreSQL snapshot.
        String result = (String) em.createNativeQuery("""
                SELECT CASE
                    WHEN p.id IS NOT NULL AND (r.id IS NULL OR r.github_repository_id <> :repository
                        OR p.github_workflow_id <> :workflow) THEN 'MISMATCH'
                    WHEN e.id IS NOT NULL AND (e.pipeline_id <> :pipeline OR e.github_run_id <> :run
                        OR e.run_attempt <> :attempt) THEN 'MISMATCH'
                    WHEN i.id IS NOT NULL AND i.pipeline_id <> :pipeline THEN 'MISMATCH'
                    WHEN p.id IS NULL OR e.id IS NULL OR i.id IS NULL THEN 'NOT_READY'
                    WHEN NOT e.failed OR NOT EXISTS (
                        SELECT 1 FROM ops.incident_runs ir
                        WHERE ir.incident_id = i.id AND ir.execution_id = e.id AND ir.role = 'ORIGIN'
                    ) THEN 'MISMATCH'
                    ELSE 'MATCH'
                END
                FROM (SELECT 1) seed
                LEFT JOIN ops.pipelines p ON p.id = :pipeline
                LEFT JOIN ops.repositories r ON r.id = p.repository_id
                LEFT JOIN ops.pipeline_executions e ON e.id = :execution
                LEFT JOIN ops.incidents i ON i.id = :incident
                """, String.class)
                .setParameter("repository", query.githubRepositoryId())
                .setParameter("workflow", query.githubWorkflowId())
                .setParameter("pipeline", query.pipelineId())
                .setParameter("run", query.githubRunId())
                .setParameter("attempt", query.runAttempt())
                .setParameter("execution", query.executionId())
                .setParameter("incident", query.incidentId()).getSingleResult();
        return Membership.valueOf(result);
    }
}
