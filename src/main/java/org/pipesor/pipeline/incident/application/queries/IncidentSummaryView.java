package org.pipesor.pipeline.incident.application.queries;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;

/**
 * Counts over one filtered, visible incident set. Every map has a key per value (zero when absent) and sums to
 * {@code total}; {@code byStatus} lists only the statuses of the requested state.
 */
public record IncidentSummaryView(long total, Map<IncidentFilter.SeverityFilter, Long> bySeverity,
                                  Map<IncidentStatus, Long> byStatus, Map<IncidentFilter.DiagnosisFilter, Long> byDiagnosis) {
    public IncidentSummaryView {
        bySeverity = Collections.unmodifiableMap(new EnumMap<>(bySeverity));
        byStatus = Collections.unmodifiableMap(new EnumMap<>(byStatus));
        byDiagnosis = Collections.unmodifiableMap(new EnumMap<>(byDiagnosis));
    }
}
