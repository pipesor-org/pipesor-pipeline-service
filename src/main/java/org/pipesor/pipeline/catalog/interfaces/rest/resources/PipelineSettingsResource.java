package org.pipesor.pipeline.catalog.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;

public record PipelineSettingsResource(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PipelineCriticality criticality,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MonitoringStatus monitoringStatus,
        String environment, String responsibleTeamRef, String responsibleTeamNameSnapshot) {
    public PipelineSettingsResource {
        if (criticality == null || monitoringStatus == null) {
            throw new IllegalArgumentException("criticality and monitoringStatus are required");
        }
    }
    public ConfigurePipelineCommand toCommand(UUID id, String actor, String correlation) {
        return new ConfigurePipelineCommand(id, actor, correlation, criticality, monitoringStatus,
                environment, responsibleTeamRef, responsibleTeamNameSnapshot);
    }
}
