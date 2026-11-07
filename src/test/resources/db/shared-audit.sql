-- Disposable development/test fixture for the shared schema in model/domain.yml.
-- Production provisioning ownership belongs to PD-035, outside service Flyway history.
CREATE SCHEMA audit;
CREATE TYPE audit."AuditCategory" AS ENUM ('diagnostic_generation', 'evidence_usage', 'human_review', 'access_and_governance_events');
CREATE TABLE audit.audit_events (
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
