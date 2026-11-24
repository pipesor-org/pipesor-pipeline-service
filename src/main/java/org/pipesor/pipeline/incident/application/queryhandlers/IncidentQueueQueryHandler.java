package org.pipesor.pipeline.incident.application.queryhandlers;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.pipesor.pipeline.incident.application.ports.IncidentQueueLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentQueuePage;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.application.services.IncidentQueueService;

/**
 * Applies ADR-020 before paging: only incidents of pipelines the actor holds a grant on and whose
 * repository current GitHub facts keep readable and admitted are candidates for the page. The queue
 * filters (ADR-027) only narrow that set, and {@code total} counts the same set.
 */
public final class IncidentQueueQueryHandler implements IncidentQueueService {
    private final Function<String, Set<String>> visibleRepositories;
    private final IncidentQueueLookup lookup;

    public IncidentQueueQueryHandler(Function<String, Set<String>> visibleRepositories, IncidentQueueLookup lookup) {
        this.visibleRepositories = Objects.requireNonNull(visibleRepositories);
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public IncidentQueuePage handle(ListIncidentQueueQuery query) {
        var repositories = query.filter().scope(visibleRepositories.apply(query.actorRef()));
        if (repositories.isEmpty()) return new IncidentQueuePage(List.of(), null, 0);
        var page = lookup.queue(query.actorRef(), repositories, query.filter(), query.after(), query.pageSize() + 1);
        var rows = page.rows();
        if (rows.size() <= query.pageSize()) return new IncidentQueuePage(rows, null, page.total());
        var items = rows.subList(0, query.pageSize());
        var last = items.getLast();
        return new IncidentQueuePage(items, new ListIncidentQueueQuery.Cursor(last.createdAt(), last.id()), page.total());
    }
}
