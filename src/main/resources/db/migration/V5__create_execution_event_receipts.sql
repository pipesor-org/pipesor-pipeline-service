-- Technical persistence for the session-approved durable receipt and ordering policy (PD-033).
-- No payload/log storage. A paused event may reference an execution not yet materialized.
CREATE TABLE ops.execution_event_receipts (
    event_id uuid PRIMARY KEY NOT NULL,
    execution_id uuid NOT NULL,
    message_digest text NOT NULL CHECK (message_digest ~ '^[0-9a-f]{64}$'),
    content_digest text NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
    source_updated_at timestamptz NOT NULL,
    source_submicro_nanos integer NOT NULL CHECK (source_submicro_nanos BETWEEN 0 AND 999),
    disposition text NOT NULL CHECK (disposition IN ('APPLIED', 'DUPLICATE', 'IGNORED_STALE', 'IGNORED_PAUSED')),
    correlation_id text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX execution_event_receipts_latest_idx
    ON ops.execution_event_receipts (execution_id, source_updated_at DESC, source_submicro_nanos DESC) WHERE disposition = 'APPLIED';
