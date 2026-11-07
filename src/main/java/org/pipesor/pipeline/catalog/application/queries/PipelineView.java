package org.pipesor.pipeline.catalog.application.queries;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;

public record PipelineView(
        UUID id,
        UUID repositoryId,
        String githubRepositoryId,
        String fullName,
        String defaultBranch,
        String githubWorkflowId,
        String workflowName,
        String workflowFile,
        String environment,
        PipelineCriticality criticality,
        MonitoringStatus monitoringStatus,
        String responsibleTeamRef,
        String responsibleTeamNameSnapshot,
        Instant createdAt,
        Set<AccessCapability> effectiveCapabilities) {
    public PipelineView {
        effectiveCapabilities = Set.copyOf(effectiveCapabilities);
    }

    public PipelineView withCapabilities(Set<AccessCapability> capabilities) {
        return new PipelineView(id, repositoryId, githubRepositoryId, fullName, defaultBranch,
                githubWorkflowId, workflowName, workflowFile, environment, criticality, monitoringStatus,
                responsibleTeamRef, responsibleTeamNameSnapshot, createdAt, capabilities);
    }
}
