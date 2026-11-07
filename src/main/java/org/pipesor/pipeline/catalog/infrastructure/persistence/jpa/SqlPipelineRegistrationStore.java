package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.RegisterPipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.ports.PipelineRegistrationStore;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.PipelineRegistrationConflict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SqlPipelineRegistrationStore implements PipelineRegistrationStore {
    private final JdbcTemplate jdbc;
    private final PipelineLookup pipelines;
    public SqlPipelineRegistrationStore(JdbcTemplate jdbc, PipelineLookup pipelines) { this.jdbc = jdbc; this.pipelines = pipelines; }

    @Override
    public PipelineView register(RegisterPipelineCommand command, GitHubConnector.Workflow verified,
            UUID repositoryId, UUID pipelineId, Instant now) {
        jdbc.update("""
                INSERT INTO ops.repositories (id, github_repository_id, full_name, default_branch, created_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (github_repository_id) DO NOTHING
                """, repositoryId, verified.repositoryId(), verified.fullName(), verified.defaultBranch(), Timestamp.from(now));
        // The unique insert and repository lock serialize simultaneous registrations, including its first workflow.
        var repository = jdbc.queryForObject("SELECT id FROM ops.repositories WHERE github_repository_id = ? FOR UPDATE",
                UUID.class, verified.repositoryId());
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ops.pipelines WHERE (repository_id = ? AND github_workflow_id = ?) OR id = ?)
                """, Boolean.class, repository, verified.workflowId(), pipelineId))) {
            throw new PipelineRegistrationConflict();
        }
        jdbc.update("UPDATE ops.repositories SET full_name = ?, default_branch = ? WHERE id = ?",
                verified.fullName(), verified.defaultBranch(), repository);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                    criticality, monitoring_status, environment, responsible_team_ref, responsible_team_name_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?::ops."PipelineCriticality", ?::ops."MonitoringStatus", ?, ?, ?, ?)
                """, pipelineId, repository, verified.workflowId(), verified.workflowName(), verified.workflowFile(),
                command.criticality().name(), command.monitoringStatus().name(), command.environment(),
                command.responsibleTeamRef(), command.responsibleTeamNameSnapshot(), Timestamp.from(now));
        return pipelines.findById(pipelineId).orElseThrow();
    }
}
