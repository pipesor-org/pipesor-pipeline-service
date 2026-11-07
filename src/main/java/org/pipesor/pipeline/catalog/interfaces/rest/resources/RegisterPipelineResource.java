package org.pipesor.pipeline.catalog.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import org.pipesor.pipeline.catalog.application.commands.RegisterPipelineCommand;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;

public record RegisterPipelineResource(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String githubRepositoryId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String githubWorkflowId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PipelineCriticality criticality,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MonitoringStatus monitoringStatus,
        String environment, String responsibleTeamRef, String responsibleTeamNameSnapshot) {
    public RegisterPipelineCommand toCommand(String actor, String correlation) {
        return new RegisterPipelineCommand(actor, correlation, githubRepositoryId, githubWorkflowId,
                criticality, monitoringStatus, environment, responsibleTeamRef, responsibleTeamNameSnapshot);
    }
}
