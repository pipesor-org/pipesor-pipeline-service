package org.pipesor.pipeline.incident.interfaces.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.commands.RecordDiagnosisSummaryCommand;
import org.pipesor.pipeline.incident.application.services.DiagnosisSummaryService;
import org.pipesor.pipeline.incident.domain.model.valueobjects.DiagnosisStatus;
import org.pipesor.pipeline.shared.interfaces.rest.CorrelationFilter;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recommendation pushes the current summary of an incident's diagnosis (ADR-027; workload {@code recommendation}, role
 * {@code Recommendation.DiagnosisSummaries.Write}, no subject token). Pipeline keeps it as a read model for the queue.
 */
@Hidden
@RestController
public class DiagnosisSummaryController {
    public static final String PATH = "/internal/v1/incidents/{incidentId}/diagnosis-summary";
    public static final String PATTERN = "/internal/v1/incidents/*/diagnosis-summary";
    private final DiagnosisSummaryService summaries;

    public DiagnosisSummaryController(DiagnosisSummaryService summaries) { this.summaries = summaries; }

    public record SummaryRequest(UUID diagnosisId, Integer version, UUID executionId, UUID pipelineId, String githubRepositoryId,
                                 String status, Integer reviewRevision, Instant reviewedAt, String probableCause, Instant createdAt) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }

    @PutMapping(path = PATH, consumes = "application/json", produces = "application/json")
    public ResponseEntity<RecordDiagnosisSummaryCommand.Result> put(@PathVariable UUID incidentId, @RequestBody SummaryRequest body,
            @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        if (body == null || body.diagnosisId() == null || body.version() == null || body.executionId() == null
                || body.pipelineId() == null || body.githubRepositoryId() == null || body.status() == null
                || body.reviewRevision() == null || body.createdAt() == null) {
            throw new IllegalArgumentException("Incomplete diagnosis summary");
        }
        var command = new RecordDiagnosisSummaryCommand(incidentId, body.diagnosisId(), body.version(), body.executionId(),
                body.pipelineId(), body.githubRepositoryId(), DiagnosisStatus.valueOf(body.status()), body.reviewRevision(),
                body.reviewedAt(), body.probableCause(), body.createdAt(), correlation);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summaries.handle(command));
        } catch (NoSuchElementException absent) {
            throw new DiagnosisEntitlementController.IncidentNotFound();
        }
    }
}
