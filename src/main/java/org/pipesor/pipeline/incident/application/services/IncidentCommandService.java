package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;

public interface IncidentCommandService {
    Incident handle(OpenIncidentCommand command);
}
