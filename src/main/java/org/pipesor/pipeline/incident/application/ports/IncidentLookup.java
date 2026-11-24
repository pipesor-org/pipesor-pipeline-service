package org.pipesor.pipeline.incident.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;

public interface IncidentLookup {
    Optional<IncidentView> findById(UUID id);
    List<IncidentView> list(UUID pipelineId, int offset, int limit);
    List<IncidentRunView> runs(UUID incidentId, int offset, int limit);
    /** The single failed ORIGIN execution of an incident. */
    Optional<UUID> originExecution(UUID incidentId);
}
