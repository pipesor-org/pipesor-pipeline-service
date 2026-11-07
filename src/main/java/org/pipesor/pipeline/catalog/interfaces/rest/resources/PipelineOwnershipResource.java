package org.pipesor.pipeline.catalog.interfaces.rest.resources;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;

/** Both nullable keys must be explicit; null clears that field. These are team references, not pipeline owners. */
public record PipelineOwnershipResource(
        @JsonProperty(required = true) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String responsibleTeamRef,
        @JsonProperty(required = true) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String responsibleTeamNameSnapshot) {
    public ConfigurePipelineCommand toCommand(UUID id, String actor, String correlation) {
        return new ConfigurePipelineCommand(id, actor, correlation, null, null, null,
                responsibleTeamRef, responsibleTeamNameSnapshot, ConfigurePipelineCommand.Section.OWNERSHIP);
    }
}
