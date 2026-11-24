package org.pipesor.pipeline.incident.application.queries;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/** Cross-pipeline incident queue for one actor, newest first, keyset-paged after {@code after}, with filters. */
public record ListIncidentQueueQuery(String actorRef, IncidentFilter filter, Sort sort, int pageSize, Cursor after) {
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

    /**
     * Queue order, newest first then id: {@code createdAt} (default) or, for RESOLVED only, {@code resolvedAt}
     * (an incident without a resolution time sorts by its creation time).
     */
    public enum Sort {
        CREATED_AT("createdAt"), RESOLVED_AT("resolvedAt");

        private final String token;
        Sort(String token) { this.token = token; }
        public String token() { return token; }

        /** {@code null} is the default order; unknown values are rejected. */
        public static Sort parse(String value) {
            if (value == null) return CREATED_AT;
            for (var sort : values()) if (sort.token.equals(value)) return sort;
            throw new IllegalArgumentException("Unknown sort");
        }

        /** Position of an incident in this order. */
        public Instant position(IncidentView incident) {
            return this == RESOLVED_AT && incident.resolvedAt() != null ? incident.resolvedAt() : incident.createdAt();
        }
    }

    /** Position of the last returned incident in (sort key desc, id desc) order. */
    public record Cursor(Instant at, UUID id) {
        public Cursor { Objects.requireNonNull(at); Objects.requireNonNull(id); }
    }

    public ListIncidentQueueQuery {
        Objects.requireNonNull(actorRef);
        Objects.requireNonNull(filter);
        Objects.requireNonNull(sort);
        if (sort == Sort.RESOLVED_AT && filter.state() != State.RESOLVED) throw new IllegalArgumentException("resolvedAt sort requires RESOLVED");
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize must be between 1 and 100");
    }
}
