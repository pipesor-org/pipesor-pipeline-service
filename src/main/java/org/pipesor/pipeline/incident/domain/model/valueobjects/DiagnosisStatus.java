package org.pipesor.pipeline.incident.domain.model.valueobjects;

/**
 * Review status of an incident's diagnosis as Recommendation reports it (ADR-027): {@code PENDING} until the first
 * human review, then the last review decision. Recommendation owns the diagnosis; Pipeline only projects this value.
 */
public enum DiagnosisStatus {
    PENDING, CONFIRMED, CORRECTED, REJECTED
}
