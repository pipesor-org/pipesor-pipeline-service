package org.pipesor.pipeline.incident.application.queries;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/** Cross-pipeline incident queue for one actor, newest first, keyset-paged after {@code after}. */
public record ListIncidentQueueQuery(String actorRef, State state, int pageSize, Cursor after) {
    /** Operations queues: OPEN covers open and in-remediation incidents, NEEDS_VALIDATION those in verification. */
    public enum State {
        OPEN(Set.of(IncidentStatus.OPEN, IncidentStatus.IN_REMEDIATION)),
        NEEDS_VALIDATION(Set.of(IncidentStatus.VERIFICATION)),
        RESOLVED(Set.of(IncidentStatus.RESOLVED));

        private final Set<IncidentStatus> statuses;
        State(Set<IncidentStatus> statuses) { this.statuses = statuses; }
        public Set<IncidentStatus> statuses() { return statuses; }
    }

    /** Position of the last returned incident in (createdAt desc, id desc) order. */
    public record Cursor(Instant createdAt, UUID id) {
        public Cursor { Objects.requireNonNull(createdAt); Objects.requireNonNull(id); }
    }

    public ListIncidentQueueQuery {
        Objects.requireNonNull(actorRef);
        Objects.requireNonNull(state);
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize must be between 1 and 100");
    }
}
