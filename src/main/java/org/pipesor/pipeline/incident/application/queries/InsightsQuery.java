package org.pipesor.pipeline.incident.application.queries;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** History Insights over the actor's visible incidents resolved within {@code [from, to]}. */
public record InsightsQuery(String actorRef, Instant from, Instant to, int top) {
    public static final Duration MAX_WINDOW = Duration.ofDays(366);
    public static final int MAX_TOP = 50;

    public InsightsQuery {
        Objects.requireNonNull(actorRef);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (!from.isBefore(to)) throw new InvalidIncidentFilter("from must precede to");
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) throw new InvalidIncidentFilter("Window too long");
        if (top < 1 || top > MAX_TOP) throw new InvalidIncidentFilter("top out of bounds");
    }
}
