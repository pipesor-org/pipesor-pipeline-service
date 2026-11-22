package org.pipesor.pipeline.execution.application.commandhandlers;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.ports.SnapshotStore;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.application.services.SnapshotDeferred;
import org.pipesor.pipeline.execution.application.services.SnapshotRejected;
import org.pipesor.pipeline.execution.domain.model.valueobjects.ExecutionOutcome;
import org.pipesor.pipeline.incident.application.ports.IncidentStore;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentDetails;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;

public final class ApplyExecutionSnapshotCommandHandler implements ExecutionSnapshotService {
    private final SnapshotStore store;
    private final IncidentStore incidents;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ApplyExecutionSnapshotCommandHandler(SnapshotStore store, IncidentStore incidents, Clock clock, Supplier<UUID> ids) {
        this.store = store; this.incidents = incidents; this.clock = clock; this.ids = ids;
    }

    @Override
    public String handle(ApplyExecutionSnapshotCommand command) {
        var event = command.snapshot();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        store.lockEvent(event.eventId());
        var receipt = store.receiptDigest(event.eventId());
        if (receipt.isPresent()) {
            if (!receipt.get().equals(command.messageDigest())) { throw new SnapshotRejected("EVENT_ID_CONFLICT"); }
            return "DUPLICATE";
        }
        var registration = store.lockPipeline(event.pipeline().githubRepositoryId(), event.pipeline().githubWorkflowId())
                .orElseThrow(() -> new SnapshotDeferred("UNKNOWN_WORKFLOW"));
        String disposition = "APPLIED";
        if (!registration.active()) {
            disposition = "IGNORED_PAUSED";
        } else {
            if (!registration.id().equals(DeterministicIds.pipeline(event.pipeline().githubRepositoryId(), event.pipeline().githubWorkflowId()))) {
                throw new SnapshotRejected("LEGACY_ID_MAPPING_REQUIRED");
            }
            var previous = store.latest(event.executionId());
            if (previous.isPresent() && event.sourceUpdatedAt().isBefore(previous.get().sourceUpdatedAt())) {
                disposition = "IGNORED_STALE";
            } else if (previous.isPresent() && event.sourceUpdatedAt().equals(previous.get().sourceUpdatedAt())) {
                if (!command.contentDigest().equals(previous.get().contentDigest())) { throw new SnapshotRejected("SNAPSHOT_VERSION_CONFLICT"); }
                disposition = "DUPLICATE";
            } else {
                var execution = event.execution();
                var stored = store.current(event.executionId(), registration.id(), execution.githubRunId(), execution.runAttempt());
                if (stored.isPresent()) {
                    if (!stored.get().pipelineId().equals(registration.id())
                            || !stored.get().runId().equals(execution.githubRunId()) || stored.get().attempt() != execution.runAttempt()) {
                        throw new SnapshotRejected("EXECUTION_IDENTITY_CONFLICT");
                    }
                    if (!stored.get().id().equals(event.executionId())) { throw new SnapshotRejected("LEGACY_ID_MAPPING_REQUIRED"); }
                    if ("completed".equals(stored.get().status()) && (!"completed".equals(execution.status())
                            || !Objects.equals(stored.get().conclusion(), execution.conclusion()))) {
                        throw new SnapshotRejected("TERMINAL_RESULT_CONFLICT");
                    }
                }
                boolean sameContent = previous.isPresent() && command.contentDigest().equals(previous.get().contentDigest());
                if (!sameContent) { store.persist(event, registration.id(), now); }
                if (event.failed()) {
                    var incidentId = DeterministicIds.incident(event.executionId());
                    if (!store.incidentExists(incidentId)) {
                        var failure = event.failure();
                        var incident = Incident.fromFailedExecution(incidentId, registration.id(),
                                new ExecutionOutcome(event.executionId(), registration.id(), true),
                                new IncidentDetails(failure.stage(), failure.summary(), failure.detectedAt(), event.correlationId(), now));
                        incidents.save(incident, ids.get());
                    }
                }
            }
        }
        store.receipt(command, disposition, now);
        return disposition;
    }
}
