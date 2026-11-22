package org.pipesor.pipeline.incident.application.queries;

import java.util.Objects;
import java.util.UUID;

/** Recommendation asks, for a verified human subject, whether the incident's diagnosis may be read. */
public record ResolveDiagnosisEntitlementQuery(String subjectRef, UUID incidentId) {
    public ResolveDiagnosisEntitlementQuery {
        Objects.requireNonNull(subjectRef);
        Objects.requireNonNull(incidentId);
    }
}
