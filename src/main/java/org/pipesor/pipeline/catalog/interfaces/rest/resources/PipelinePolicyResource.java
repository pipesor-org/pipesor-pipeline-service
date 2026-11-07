package org.pipesor.pipeline.catalog.interfaces.rest.resources;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;

/** All section keys are required; explicit null clears environment. */
public record PipelinePolicyResource(
        @JsonProperty(required = true) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PipelineCriticality criticality,
        @JsonProperty(required = true) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MonitoringStatus monitoringStatus,
        @JsonProperty(required = true) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String environment) {
    public ConfigurePipelineCommand toCommand(UUID id, String actor, String correlation) {
        return new ConfigurePipelineCommand(id, actor, correlation, criticality, monitoringStatus, environment,
                null, null, ConfigurePipelineCommand.Section.POLICY);
    }
}
