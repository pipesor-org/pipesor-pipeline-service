package org.pipesor.pipeline.incident.application.queries;

import java.util.Objects;
import java.util.UUID;

public record GetIncidentQuery(UUID id) {
    public GetIncidentQuery {
        Objects.requireNonNull(id, "id is required");
    }
}
