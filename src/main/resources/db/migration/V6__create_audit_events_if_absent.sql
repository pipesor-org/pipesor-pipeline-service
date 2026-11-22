-- ADR-011 / runtime manifest: Pipeline is the MVP owner of audit.audit_events.
-- Idempotent so environments where the table was provisioned earlier keep it unchanged.
CREATE SCHEMA IF NOT EXISTS audit;

DO $$
BEGIN
    CREATE TYPE audit."AuditCategory" AS ENUM ('diagnostic_generation', 'evidence_usage', 'human_review', 'access_and_governance_events');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END
$$;

CREATE TABLE IF NOT EXISTS audit.audit_events (
    id uuid PRIMARY KEY NOT NULL,
    category audit."AuditCategory" NOT NULL,
    correlation_id text NOT NULL,
    actor_ref text,
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    action text NOT NULL,
    before_state jsonb,
    after_state jsonb,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL
);
