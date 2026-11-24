package org.pipesor.pipeline.incident.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.InsightsView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/**
 * Cross-pipeline reads restricted to incidents of pipelines the actor holds a grant on, within the given GitHub
 * repositories (already narrowed to what current GitHub facts keep readable and admitted), before paging (ADR-020).
 */
public interface IncidentQueueLookup {
    /** Up to {@code limit} matching incidents after {@code after}, newest first, and the exact number of matches. */
    Page queue(String actorRef, Set<String> repositories, IncidentFilter filter, ListIncidentQueueQuery.Cursor after, int limit);

    /** Number of matching incidents per (severity, status, diagnosis) combination. */
    List<Count> counts(String actorRef, Set<String> repositories, IncidentFilter filter);

    /** Every visible incident resolved within {@code [from, to]}. */
    void resolved(String actorRef, Set<String> repositories, Instant from, Instant to, Consumer<InsightsView.Row> sink);

    record Page(List<IncidentView> rows, long total) {
        public Page { rows = List.copyOf(rows); }
    }

    record Count(IncidentFilter.SeverityFilter severity, IncidentStatus status, IncidentFilter.DiagnosisFilter diagnosis, long count) {}
}
