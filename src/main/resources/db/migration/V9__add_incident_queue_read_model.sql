-- ADR-027: summary of each incident's diagnosis, projected from Recommendation (which owns the diagnosis and its
-- reviews) through PUT /internal/v1/incidents/{incidentId}/diagnosis-summary. Read model only: it lets the queue
-- filter, count and compute Insights by diagnosis status before paging. No row means no admitted diagnosis (ABSENT).
CREATE TABLE ops.incident_diagnoses (
    incident_id uuid PRIMARY KEY NOT NULL,
    diagnosis_id uuid NOT NULL,
    diagnosis_version integer NOT NULL,
    execution_id uuid NOT NULL,
    status text NOT NULL,
    review_revision integer NOT NULL,
    reviewed_at timestamptz,
    probable_cause text,
    diagnosis_created_at timestamptz NOT NULL,
    correlation_id text NOT NULL,
    projected_at timestamptz NOT NULL,
    CONSTRAINT incident_diagnoses_version_ck CHECK (diagnosis_version > 0),
    CONSTRAINT incident_diagnoses_status_ck CHECK (status IN ('PENDING', 'CONFIRMED', 'CORRECTED', 'REJECTED')),
    CONSTRAINT incident_diagnoses_review_ck CHECK (review_revision >= 0
        AND (status = 'PENDING') = (review_revision = 0)
        AND (reviewed_at IS NULL) = (review_revision = 0)),
    CONSTRAINT incident_diagnoses_cause_ck CHECK (probable_cause IS NULL OR char_length(probable_cause) BETWEEN 1 AND 1000),
    CONSTRAINT incident_diagnoses_incident_id FOREIGN KEY (incident_id) REFERENCES ops.incidents (id) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT incident_diagnoses_execution_id FOREIGN KEY (execution_id) REFERENCES ops.pipeline_executions (id) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE INDEX incident_diagnoses_status_ix ON ops.incident_diagnoses (status);

-- Queue filters (ADR-027). Keyset paging runs on (created_at DESC, id DESC) within the requested statuses.
CREATE INDEX incidents_status_created_ix ON ops.incidents (status, created_at DESC, id DESC);
CREATE INDEX incidents_pipeline_status_created_ix ON ops.incidents (pipeline_id, status, created_at DESC, id DESC);
CREATE INDEX incidents_severity_status_ix ON ops.incidents (severity, status);
CREATE INDEX incidents_stage_status_ix ON ops.incidents (stage, status);
-- Insights read resolved incidents by resolution time.
CREATE INDEX incidents_resolved_at_ix ON ops.incidents (resolved_at) WHERE status = 'RESOLVED';
-- Authorization before paging: the actor's granted pipelines.
CREATE INDEX pipeline_access_grants_principal_ix ON ops.pipeline_access_grants (principal_ref, pipeline_id);
