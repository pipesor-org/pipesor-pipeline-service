-- Confirmed ops ownership and fields: model/domain.yml; ADR-018 and DD-002/013.

CREATE TYPE ops."IncidentStatus" AS ENUM ('OPEN', 'IN_REMEDIATION', 'VERIFICATION', 'RESOLVED');

CREATE TYPE ops."IncidentRunRole" AS ENUM ('ORIGIN', 'VERIFICATION');

CREATE TYPE ops."PipelineStage" AS ENUM ('BUILD', 'TESTING', 'QUALITY_GATE', 'DEPLOYMENT', 'CONTAINER', 'INFRASTRUCTURE');

CREATE TYPE ops."MonitoringStatus" AS ENUM ('ACTIVE', 'PAUSED');

CREATE TYPE ops."Severity" AS ENUM ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW');

CREATE TYPE ops."PipelineCriticality" AS ENUM ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW');

CREATE TABLE ops.pipelines (
    id uuid PRIMARY KEY NOT NULL,
    repository_id uuid NOT NULL,
    github_workflow_id text NOT NULL,
    workflow_name text NOT NULL,
    workflow_file text NOT NULL,
    environment text,
    criticality ops."PipelineCriticality" NOT NULL,
    monitoring_status ops."MonitoringStatus" NOT NULL,
    responsible_team_ref text,
    responsible_team_name_snapshot text,
    created_at timestamptz NOT NULL,
    CONSTRAINT pipelines_repository_id_github_workflow_id_uq UNIQUE (repository_id, github_workflow_id),
    CONSTRAINT pipelines_repository_id FOREIGN KEY (repository_id) REFERENCES ops.repositories (id) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE TABLE ops.pipeline_executions (
    id uuid PRIMARY KEY NOT NULL,
    pipeline_id uuid NOT NULL,
    github_run_id text NOT NULL,
    run_attempt integer NOT NULL,
    branch text NOT NULL,
    commit_sha text NOT NULL,
    external_status text NOT NULL,
    external_conclusion text,
    failed boolean NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    correlation_id text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pipeline_executions_pipeline_id_github_run_id_run_attempt_uq UNIQUE (pipeline_id, github_run_id, run_attempt),
    CONSTRAINT pipeline_executions_check_1 CHECK (run_attempt > 0),
    CONSTRAINT pipeline_executions_pipeline_id FOREIGN KEY (pipeline_id) REFERENCES ops.pipelines (id) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE TABLE ops.execution_stages (
    id uuid PRIMARY KEY NOT NULL,
    execution_id uuid NOT NULL,
    stage ops."PipelineStage" NOT NULL,
    external_status text,
    started_at timestamptz,
    completed_at timestamptz,
    correlation_id text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT execution_stages_execution_id_stage_uq UNIQUE (execution_id, stage),
    CONSTRAINT execution_stages_execution_id FOREIGN KEY (execution_id) REFERENCES ops.pipeline_executions (id) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE TABLE ops.incidents (
    id uuid PRIMARY KEY NOT NULL,
    pipeline_id uuid NOT NULL,
    status ops."IncidentStatus" NOT NULL,
    severity ops."Severity",
    stage ops."PipelineStage" NOT NULL,
    summary text NOT NULL,
    assignee_ref text,
    assignee_display_name_snapshot text,
    detected_at timestamptz NOT NULL,
    remediation_started_at timestamptz,
    verification_started_at timestamptz,
    resolved_at timestamptz,
    correlation_id text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT incidents_pipeline_id FOREIGN KEY (pipeline_id) REFERENCES ops.pipelines (id) DEFERRABLE INITIALLY IMMEDIATE
);

CREATE TABLE ops.incident_runs (
    id uuid PRIMARY KEY NOT NULL,
    incident_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    role ops."IncidentRunRole" NOT NULL,
    correlation_id text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT incident_runs_incident_id_execution_id_uq UNIQUE (incident_id, execution_id),
    CONSTRAINT incident_runs_incident_id FOREIGN KEY (incident_id) REFERENCES ops.incidents (id) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT incident_runs_execution_id FOREIGN KEY (execution_id) REFERENCES ops.pipeline_executions (id) DEFERRABLE INITIALLY IMMEDIATE
);
