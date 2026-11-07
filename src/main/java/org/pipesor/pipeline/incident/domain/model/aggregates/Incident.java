package org.pipesor.pipeline.incident.domain.model.aggregates;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.execution.domain.model.valueobjects.ExecutionOutcome;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentAction;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentDetails;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentLifecycle;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.InvalidIncidentTransition;
import org.pipesor.pipeline.incident.domain.model.valueobjects.VerificationExecution;
import org.pipesor.pipeline.shared.domain.model.Entity;

/** Aggregate root protecting the incident's immutable failed origin. */
public final class Incident extends Entity {

    private final UUID pipelineId;
    private final ExecutionOutcome origin;
    private final IncidentDetails details;
    private IncidentLifecycle lifecycle = IncidentLifecycle.open();

    private Incident(UUID id, UUID pipelineId, ExecutionOutcome origin, IncidentDetails details) {
        super(id);
        this.pipelineId = Objects.requireNonNull(pipelineId, "pipelineId is required");
        this.origin = Objects.requireNonNull(origin, "origin is required");
        this.details = Objects.requireNonNull(details, "details are required");
        if (!origin.failed()) {
            throw new IllegalArgumentException("An incident must originate from a failed execution");
        }
        if (!pipelineId.equals(origin.pipelineId())) {
            throw new IllegalArgumentException("The origin execution must belong to the incident pipeline");
        }
    }

    public static Incident fromFailedExecution(UUID id, UUID pipelineId, ExecutionOutcome origin, IncidentDetails details) {
        return new Incident(id, pipelineId, origin, details);
    }

    public static Incident restore(UUID id, UUID pipelineId, ExecutionOutcome origin,
                                   IncidentDetails details, IncidentLifecycle lifecycle) {
        var incident = new Incident(id, pipelineId, origin, details);
        incident.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle is required");
        return incident;
    }

    public IncidentLifecycle getLifecycle() { return lifecycle; }

    public void transition(IncidentAction action, VerificationExecution execution, Instant now) {
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(now, "timestamp is required");
        var state = lifecycle;
        switch (action) {
            case START_REMEDIATION -> {
                requireState(IncidentStatus.OPEN);
                lifecycle = new IncidentLifecycle(IncidentStatus.IN_REMEDIATION, now, null, null,
                        state.verificationExecutionIds());
            }
            case START_VERIFICATION -> {
                requireState(IncidentStatus.IN_REMEDIATION);
                requireAfterIntervention(execution);
                var ids = new HashSet<>(state.verificationExecutionIds());
                ids.add(execution.id());
                lifecycle = new IncidentLifecycle(IncidentStatus.VERIFICATION, state.remediationStartedAt(),
                        now, null, ids);
            }
            case RETRY_REMEDIATION, RESOLVE -> {
                requireState(IncidentStatus.VERIFICATION);
                requireAfterIntervention(execution);
                if (!state.verificationExecutionIds().contains(execution.id())) {
                    throw new InvalidIncidentTransition("Execution is not linked as verification");
                }
                if (action == IncidentAction.RESOLVE) {
                    if (!execution.successful()) {
                        throw new InvalidIncidentTransition("Resolution requires successful verification");
                    }
                    lifecycle = new IncidentLifecycle(IncidentStatus.RESOLVED, state.remediationStartedAt(),
                            state.verificationStartedAt(), now, state.verificationExecutionIds());
                } else {
                    if (!execution.failed()) {
                        throw new InvalidIncidentTransition("Returning to remediation requires failed verification");
                    }
                    lifecycle = new IncidentLifecycle(IncidentStatus.IN_REMEDIATION, now,
                            state.verificationStartedAt(), null, state.verificationExecutionIds());
                }
            }
        }
    }

    private void requireAfterIntervention(VerificationExecution execution) {
        Objects.requireNonNull(execution, "verification execution is required");
        if (execution.startedAt() == null || lifecycle.remediationStartedAt() == null
                || !execution.startedAt().isAfter(lifecycle.remediationStartedAt())) {
            throw new InvalidIncidentTransition("Verification must start after the current human intervention");
        }
    }

    private void requireState(IncidentStatus expected) {
        if (lifecycle.status() != expected) {
            throw new IllegalStateException("Transition unavailable from " + lifecycle.status());
        }
    }

    public IncidentDetails getDetails() {
        return details;
    }

    public UUID getPipelineId() {
        return pipelineId;
    }

    public ExecutionOutcome getOrigin() {
        return origin;
    }
}
