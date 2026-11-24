package org.pipesor.pipeline.incident.application.queryhandlers;

import java.util.EnumMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.pipesor.pipeline.incident.application.ports.IncidentQueueLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.IncidentSummaryQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentSummaryView;
import org.pipesor.pipeline.incident.application.services.IncidentSummaryService;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/** Counts of the queue's filtered, visible set (same authorization before counting as the queue, ADR-020/027). */
public final class IncidentSummaryQueryHandler implements IncidentSummaryService {
    private final Function<String, Set<String>> visibleRepositories;
    private final IncidentQueueLookup lookup;

    public IncidentSummaryQueryHandler(Function<String, Set<String>> visibleRepositories, IncidentQueueLookup lookup) {
        this.visibleRepositories = Objects.requireNonNull(visibleRepositories);
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public IncidentSummaryView handle(IncidentSummaryQuery query) {
        var severity = new EnumMap<IncidentFilter.SeverityFilter, Long>(IncidentFilter.SeverityFilter.class);
        for (var value : IncidentFilter.SeverityFilter.values()) severity.put(value, 0L);
        var status = new EnumMap<IncidentStatus, Long>(IncidentStatus.class);
        for (var value : query.filter().state().statuses()) status.put(value, 0L);
        var diagnosis = new EnumMap<IncidentFilter.DiagnosisFilter, Long>(IncidentFilter.DiagnosisFilter.class);
        for (var value : IncidentFilter.DiagnosisFilter.values()) diagnosis.put(value, 0L);
        long total = 0;
        var repositories = query.filter().scope(visibleRepositories.apply(query.actorRef()));
        if (!repositories.isEmpty()) {
            for (var count : lookup.counts(query.actorRef(), repositories, query.filter())) {
                total += count.count();
                severity.merge(count.severity(), count.count(), Long::sum);
                status.merge(count.status(), count.count(), Long::sum);
                diagnosis.merge(count.diagnosis(), count.count(), Long::sum);
            }
        }
        return new IncidentSummaryView(total, severity, status, diagnosis);
    }
}
