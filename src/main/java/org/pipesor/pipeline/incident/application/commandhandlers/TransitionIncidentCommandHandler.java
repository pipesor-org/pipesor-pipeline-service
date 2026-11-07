package org.pipesor.pipeline.incident.application.commandhandlers;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.incident.application.commands.TransitionIncidentCommand;
import org.pipesor.pipeline.incident.application.ports.IncidentStore;
import org.pipesor.pipeline.incident.application.ports.VerificationLookup;
import org.pipesor.pipeline.incident.application.services.IncidentLifecycleService;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentAction;
import org.pipesor.pipeline.shared.application.ports.AuditLog;

public final class TransitionIncidentCommandHandler implements IncidentLifecycleService {
    private final IncidentStore incidents;
    private final VerificationLookup executions;
    private final PipelineAccess access;
    private final AuditLog audit;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public TransitionIncidentCommandHandler(IncidentStore incidents, VerificationLookup executions,
            PipelineAccess access, AuditLog audit, Clock clock, Supplier<UUID> ids) {
        this.incidents = incidents;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public Incident handle(TransitionIncidentCommand command) {
        var incident = incidents.lock(command.incidentId()).orElseThrow(
                () -> new NoSuchElementException("Incident not found"));
        access.require(command.actorRef(), incident.getPipelineId(), AccessCapability.OPERATE);
        var execution = command.executionId() == null ? null : executions.lockVerification(command.executionId())
                .orElseThrow(() -> new NoSuchElementException("Verification execution not found"));
        if (execution != null) {
            access.require(command.actorRef(), execution.pipelineId(), AccessCapability.VIEW);
        }
        var before = incident.getLifecycle().status();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        incident.transition(command.action(), execution, now);
        UUID link = command.action() == IncidentAction.START_VERIFICATION ? command.executionId() : null;
        incidents.update(incident, link, link == null ? null : ids.get(), command.correlationId(), now);
        var after = new HashMap<String, String>();
        after.put("status", incident.getLifecycle().status().name());
        if (execution != null) { after.put("executionId", execution.id().toString()); }
        audit.append(ids.get(), "incident", incident.getId(), command.action().name(), command.actorRef(),
                command.correlationId(), now, Map.of("status", before.name()), after);
        return incident;
    }
}
