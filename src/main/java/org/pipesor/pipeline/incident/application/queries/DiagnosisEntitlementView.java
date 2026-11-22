package org.pipesor.pipeline.incident.application.queries;

import java.util.List;
import java.util.UUID;

/**
 * Confirmed entitlement: the incident, its single failed ORIGIN execution, the GitHub scope verified
 * now and the subject's effective incident capabilities ({@code VIEW}, plus {@code OPERATE} when allowed).
 */
public record DiagnosisEntitlementView(UUID incidentId, UUID pipelineId, UUID executionId, String githubInstallationId,
        String githubRepositoryId, UUID tenantId, List<String> capabilities) {
    public DiagnosisEntitlementView { capabilities = List.copyOf(capabilities); }
}
