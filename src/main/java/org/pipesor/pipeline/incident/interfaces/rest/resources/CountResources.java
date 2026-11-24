package org.pipesor.pipeline.incident.interfaces.rest.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter.DiagnosisFilter;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter.SeverityFilter;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/** Count objects with one explicit key per contract value. */
public final class CountResources {
    private CountResources() {}

    public record BySeverity(@JsonProperty("CRITICAL") long critical, @JsonProperty("HIGH") long high,
                             @JsonProperty("MEDIUM") long medium, @JsonProperty("LOW") long low,
                             @JsonProperty("UNCLASSIFIED") long unclassified) {
        public static BySeverity of(Map<SeverityFilter, Long> counts) {
            return new BySeverity(counts.getOrDefault(SeverityFilter.CRITICAL, 0L), counts.getOrDefault(SeverityFilter.HIGH, 0L),
                    counts.getOrDefault(SeverityFilter.MEDIUM, 0L), counts.getOrDefault(SeverityFilter.LOW, 0L),
                    counts.getOrDefault(SeverityFilter.UNCLASSIFIED, 0L));
        }
    }

    /** Only the statuses of the requested state are present. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "OPEN, IN_REMEDIATION and VERIFICATION for state=OPEN; RESOLVED for state=RESOLVED")
    public record ByStatus(@JsonProperty("OPEN") Long open, @JsonProperty("IN_REMEDIATION") Long inRemediation,
                           @JsonProperty("VERIFICATION") Long verification, @JsonProperty("RESOLVED") Long resolved) {
        public static ByStatus of(Map<IncidentStatus, Long> counts) {
            return new ByStatus(counts.get(IncidentStatus.OPEN), counts.get(IncidentStatus.IN_REMEDIATION),
                    counts.get(IncidentStatus.VERIFICATION), counts.get(IncidentStatus.RESOLVED));
        }
    }

    public record ByDiagnosis(@JsonProperty("PENDING") long pending, @JsonProperty("CONFIRMED") long confirmed,
                              @JsonProperty("CORRECTED") long corrected, @JsonProperty("REJECTED") long rejected,
                              @JsonProperty("ABSENT") long absent) {
        public static ByDiagnosis of(Map<DiagnosisFilter, Long> counts) {
            return new ByDiagnosis(counts.getOrDefault(DiagnosisFilter.PENDING, 0L), counts.getOrDefault(DiagnosisFilter.CONFIRMED, 0L),
                    counts.getOrDefault(DiagnosisFilter.CORRECTED, 0L), counts.getOrDefault(DiagnosisFilter.REJECTED, 0L),
                    counts.getOrDefault(DiagnosisFilter.ABSENT, 0L));
        }
    }
}
