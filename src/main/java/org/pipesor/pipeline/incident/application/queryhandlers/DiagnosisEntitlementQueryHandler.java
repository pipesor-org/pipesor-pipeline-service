package org.pipesor.pipeline.incident.application.queryhandlers;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.queries.DiagnosisEntitlementView;
import org.pipesor.pipeline.incident.application.queries.ResolveDiagnosisEntitlementQuery;
import org.pipesor.pipeline.incident.application.services.DiagnosisEntitlementService;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentOriginMissing;

/** VIEW grant plus current GitHub access (ADR-020) on the incident's pipeline, then its ORIGIN execution. */
public final class DiagnosisEntitlementQueryHandler implements DiagnosisEntitlementService {
    private final IncidentLookup incidents;
    private final HumanPipelineAccess access;

    public DiagnosisEntitlementQueryHandler(IncidentLookup incidents, HumanPipelineAccess access) {
        this.incidents = Objects.requireNonNull(incidents);
        this.access = Objects.requireNonNull(access);
    }

    @Override
    public DiagnosisEntitlementView handle(ResolveDiagnosisEntitlementQuery query) {
        var incident = incidents.findById(query.incidentId()).orElseThrow(NoSuchElementException::new);
        var verified = access.verified(query.subjectRef(), incident.pipelineId(), AccessCapability.VIEW);
        if (!verified.capabilities().contains(AccessCapability.VIEW) || verified.repository() == null) {
            throw new SecurityException("Diagnosis read entitlement denied");
        }
        var origin = incidents.originExecution(incident.id()).orElseThrow(IncidentOriginMissing::new);
        var tenant = UUID.fromString(query.subjectRef().split(":", -1)[0]);
        var capabilities = verified.capabilities().contains(AccessCapability.OPERATE)
                ? List.of(AccessCapability.VIEW.name(), AccessCapability.OPERATE.name()) : List.of(AccessCapability.VIEW.name());
        return new DiagnosisEntitlementView(incident.id(), incident.pipelineId(), origin, verified.repository().installationId(),
                verified.pipeline().githubRepositoryId(), tenant, capabilities);
    }
}
