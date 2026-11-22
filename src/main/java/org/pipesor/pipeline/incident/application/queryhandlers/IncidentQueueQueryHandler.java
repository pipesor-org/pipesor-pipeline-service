package org.pipesor.pipeline.incident.application.queryhandlers;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentQueuePage;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.application.services.IncidentQueueService;

/**
 * Applies ADR-020 before paging: only incidents of pipelines the actor holds a grant on and whose
 * repository current GitHub facts keep readable and admitted are candidates for the page.
 */
public final class IncidentQueueQueryHandler implements IncidentQueueService {
    private final Function<String, Set<String>> visibleRepositories;
    private final IncidentLookup lookup;

    public IncidentQueueQueryHandler(Function<String, Set<String>> visibleRepositories, IncidentLookup lookup) {
        this.visibleRepositories = Objects.requireNonNull(visibleRepositories);
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public IncidentQueuePage handle(ListIncidentQueueQuery query) {
        var repositories = visibleRepositories.apply(query.actorRef());
        if (repositories.isEmpty()) return new IncidentQueuePage(java.util.List.of(), null);
        var rows = lookup.queue(query.actorRef(), repositories, query.state().statuses(), query.after(), query.pageSize() + 1);
        if (rows.size() <= query.pageSize()) return new IncidentQueuePage(rows, null);
        var page = rows.subList(0, query.pageSize());
        var last = page.getLast();
        return new IncidentQueuePage(page, new ListIncidentQueueQuery.Cursor(last.createdAt(), last.id()));
    }
}
