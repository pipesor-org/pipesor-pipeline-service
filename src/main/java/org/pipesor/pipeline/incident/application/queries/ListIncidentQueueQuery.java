package org.pipesor.pipeline.incident.application.queries;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/** Cross-pipeline incident queue for one actor, newest first, keyset-paged after {@code after}, with filters. */
public record ListIncidentQueueQuery(String actorRef, IncidentFilter filter, int pageSize, Cursor after) {
    /**
     * Lifecycle queues: OPEN is every active incident (open, in remediation or in verification) and RESOLVED the
     * resolved ones. Diagnosis validation is not a lifecycle state: Needs validation is the RESOLVED queue filtered by
     * the diagnosis status that Recommendation projects into Pipeline (ADR-027).
     */
    public enum State {
        OPEN(Set.of(IncidentStatus.OPEN, IncidentStatus.IN_REMEDIATION, IncidentStatus.VERIFICATION)),
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
        Objects.requireNonNull(filter);
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize must be between 1 and 100");
    }
}
