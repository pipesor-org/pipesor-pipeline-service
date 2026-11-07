package org.pipesor.pipeline.catalog.application.commands;

import org.pipesor.pipeline.catalog.application.services.PipelineAuditMetadata;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;

public record RegisterPipelineCommand(String actorRef, String correlationId, String githubRepositoryId,
        String githubWorkflowId, PipelineCriticality criticality, MonitoringStatus monitoringStatus,
        String environment, String responsibleTeamRef, String responsibleTeamNameSnapshot) {
    public RegisterPipelineCommand {
        PipelineAuditMetadata.validateInputs(environment, responsibleTeamRef, responsibleTeamNameSnapshot);
        DeterministicIds.pipeline(githubRepositoryId, githubWorkflowId);
        if (actorRef == null || actorRef.isBlank() || correlationId == null || correlationId.isBlank()
                || criticality == null || monitoringStatus == null) {
            throw new IllegalArgumentException("Actor, correlation, criticality and monitoringStatus required");
        }
    }
}
