package org.pipesor.pipeline.catalog.application.commands;

import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.services.PipelineAuditMetadata;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;

public record ConfigurePipelineCommand(UUID pipelineId, String actorRef, String correlationId,
        PipelineCriticality criticality, MonitoringStatus monitoringStatus, String environment,
        String responsibleTeamRef, String responsibleTeamNameSnapshot, Section section) {
    public enum Section { ALL, POLICY, OWNERSHIP }

    public ConfigurePipelineCommand(UUID pipelineId, String actorRef, String correlationId,
            PipelineCriticality criticality, MonitoringStatus monitoringStatus, String environment,
            String responsibleTeamRef, String responsibleTeamNameSnapshot) {
        this(pipelineId, actorRef, correlationId, criticality, monitoringStatus, environment,
                responsibleTeamRef, responsibleTeamNameSnapshot, Section.ALL);
    }

    public ConfigurePipelineCommand {
        PipelineAuditMetadata.validateInputs(environment, responsibleTeamRef, responsibleTeamNameSnapshot);
        Objects.requireNonNull(pipelineId, "pipelineId is required");
        Objects.requireNonNull(section, "section is required");
        if (section != Section.OWNERSHIP && (criticality == null || monitoringStatus == null)) {
            throw new IllegalArgumentException("criticality and monitoringStatus are required");
        }
        if (actorRef == null || actorRef.isBlank() || correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Actor and correlation are required");
        }
    }
}
