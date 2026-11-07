package org.pipesor.pipeline.incident.application.commandhandlers;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.ports.IncidentOriginLookup;
import org.pipesor.pipeline.incident.application.ports.IncidentStore;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentDetails;

public class OpenIncidentCommandHandler implements IncidentCommandService {
    private final IncidentOriginLookup executions;
    private final IncidentStore incidents;
    private final Clock clock;
    private final Supplier<UUID> identities;

    public OpenIncidentCommandHandler(IncidentOriginLookup executions, IncidentStore incidents,
                                      Clock clock, Supplier<UUID> identities) {
        this.executions = executions;
        this.incidents = incidents;
        this.clock = clock;
        this.identities = identities;
    }

    @Override
    public Incident handle(OpenIncidentCommand command) {
        var origin = executions.lockExecution(command.executionId())
                .orElseThrow(() -> new NoSuchElementException("Execution not found: " + command.executionId()));
        var details = new IncidentDetails(command.stage(), command.summary(), command.detectedAt(),
                command.correlationId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        var incident = Incident.fromFailedExecution(identities.get(), command.pipelineId(), origin, details);
        incidents.save(incident, identities.get());
        return incident;
    }
}
