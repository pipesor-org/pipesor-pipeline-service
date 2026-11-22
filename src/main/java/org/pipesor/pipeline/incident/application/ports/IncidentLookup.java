package org.pipesor.pipeline.incident.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

public interface IncidentLookup {
    Optional<IncidentView> findById(UUID id);
    List<IncidentView> list(UUID pipelineId, int offset, int limit);
    List<IncidentRunView> runs(UUID incidentId, int offset, int limit);
    /** Incidents of pipelines the actor holds any grant on, within the given GitHub repositories, newest first. */
    List<IncidentView> queue(String actorRef, Set<String> githubRepositoryIds, Set<IncidentStatus> statuses,
                             ListIncidentQueueQuery.Cursor after, int limit);
    /** The single failed ORIGIN execution of an incident. */
    Optional<UUID> originExecution(UUID incidentId);
}
