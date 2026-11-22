package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.queries.DiagnosisEntitlementView;
import org.pipesor.pipeline.incident.application.queries.ResolveDiagnosisEntitlementQuery;

public interface DiagnosisEntitlementService {
    /** Throws NoSuchElementException when absent, SecurityException when the subject lacks VIEW or GitHub access. */
    DiagnosisEntitlementView handle(ResolveDiagnosisEntitlementQuery query);
}
