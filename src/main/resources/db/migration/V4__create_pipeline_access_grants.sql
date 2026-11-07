-- Session-approved per-pipeline capabilities; adopts the source model's provisional storage shape locally.
CREATE TYPE ops."AccessCapability" AS ENUM ('VIEW', 'OPERATE', 'CONFIGURE');
CREATE TABLE ops.pipeline_access_grants (
    id uuid PRIMARY KEY NOT NULL,
    pipeline_id uuid NOT NULL,
    principal_ref text NOT NULL,
    capability ops."AccessCapability" NOT NULL,
    correlation_id text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pipeline_access_grants_pipeline_id_principal_ref_capability_uq UNIQUE (pipeline_id, principal_ref, capability),
    CONSTRAINT pipeline_access_grants_pipeline_id FOREIGN KEY (pipeline_id) REFERENCES ops.pipelines (id) DEFERRABLE INITIALLY IMMEDIATE
);
