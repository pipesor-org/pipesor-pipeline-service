package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.commands.TransitionIncidentCommand;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;

public interface IncidentLifecycleService {
    Incident handle(TransitionIncidentCommand command);
}
