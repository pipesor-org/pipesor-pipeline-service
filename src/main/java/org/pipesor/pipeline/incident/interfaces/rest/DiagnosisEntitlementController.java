package org.pipesor.pipeline.incident.interfaces.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.DiagnosisEntitlementView;
import org.pipesor.pipeline.incident.application.queries.ResolveDiagnosisEntitlementQuery;
import org.pipesor.pipeline.incident.application.services.DiagnosisEntitlementService;
import org.pipesor.pipeline.shared.interfaces.rest.RequestContext;
import org.pipesor.pipeline.shared.security.SubjectTokens;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recommendation resolves whether its verified subject may read an incident's diagnosis
 * ({@code Recommendation.DiagnosisEntitlements.Resolve} + subject token). The subject is the subject
 * token's session, never a body field. A subject without VIEW and an absent incident both yield 404.
 */
@Hidden
@RestController
public class DiagnosisEntitlementController {
    public static final String PATH = "/internal/v1/diagnosis-read-entitlements";
    private final DiagnosisEntitlementService entitlements;

    public DiagnosisEntitlementController(DiagnosisEntitlementService entitlements) { this.entitlements = entitlements; }

    public record EntitlementRequest(UUID incidentId) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    @PostMapping(path = PATH, consumes = "application/json", produces = "application/json")
    public ResponseEntity<DiagnosisEntitlementView> resolve(HttpServletRequest request, @RequestBody EntitlementRequest body) {
        if (body == null || body.incidentId() == null) throw new IllegalArgumentException("incidentId is required");
        var subject = SubjectTokens.subject(request).orElseThrow(() -> new SecurityException("Subject token required"));
        try {
            var view = entitlements.handle(new ResolveDiagnosisEntitlementQuery(RequestContext.actor(subject), body.incidentId()));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
        } catch (NoSuchElementException | SecurityException absentOrDenied) {
            // Denial and absence are indistinguishable so existence is not revealed.
            throw new IncidentNotFound();
        }
    }

    /** Absent incident on the entitlement route (problem code {@code INCIDENT_NOT_FOUND}). */
    public static final class IncidentNotFound extends NoSuchElementException {
        public IncidentNotFound() { super("Incident not found"); }
    }
}
