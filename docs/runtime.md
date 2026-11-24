# Runtime: profiles, configuration and routes

Sources: [runtime manifest](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/runtime-manifest.md),
[platform identity contract v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/platform-identity-v1.md),
[MVP HTTP API v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/mvp-http-api-v1.md).

## Profiles

One image; the profile only selects adapters.

| Concern | `local` | `azure` |
|---|---|---|
| Signing key | PEM file `PIPESOR_SIGNING_KEY_FILE` (mode 0600/0400) | Key Vault key `PIPESOR_SIGNING_KEY_ID` via `DefaultAzureCredential` |
| PostgreSQL | `PIPESOR_DATABASE_USERNAME` / `PIPESOR_DATABASE_PASSWORD` | Entra token through `com.azure:azure-identity-extensions` (`AzurePostgresqlAuthenticationPlugin`); no password |
| Service Bus | `PIPESOR_SERVICE_BUS_CONNECTION_STRING` (emulator) | `PIPESOR_SERVICE_BUS_NAMESPACE` + `DefaultAzureCredential` |
| Transport | plain HTTP allowed to loopback hosts only | HTTPS only |
| OpenAPI | `/v3/api-docs`, Swagger UI | disabled |

Startup fails when a required value is missing, when a file signing key, a Service Bus
connection string or a database password is configured under `azure`, or when an outbound or
JWKS URI is not HTTPS (except loopback under `local`).

## Configuration

| Variable | Use |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` or `azure` |
| `PIPESOR_TENANT_ID`, `PIPESOR_WORKLOAD_ID` (`pipeline`), `PIPESOR_SECURITY_AUDIENCE` (`urn:pipesor:api:pipeline`) | identity |
| `PIPESOR_SIGNING_KEY_FILE` or `PIPESOR_SIGNING_KEY_ID` | workload signing key, published at `/.well-known/jwks.json` |
| `PIPESOR_SECURITY_SESSION_JWKSURI` | Ingestion's session JWKS |
| `PIPESOR_SECURITY_TRUSTEDWORKLOADS_<n>_ID/_JWKSURI/_ROLES` | inbound callers: `correlation` (`Correlation.OperationalReferences.Check`), `log-event` (`Evidence.Check`), `recommendation` (`Recommendation.DiagnosisEntitlements.Resolve,Recommendation.DiagnosisSummaries.Write`) |
| `PIPESOR_ENDPOINTS_INGESTION` | Ingestion base URL (GitHub facts, machine-scope checks, capture workflow scopes) |
| `PIPESOR_CAPTURE_SCOPE_RECONCILE_INTERVAL`, `PIPESOR_CAPTURE_SCOPE_RECONCILE_ENABLED` | capture scope reconciliation (ADR-023): default `5m` (minimum 10 s) and `true`; tests disable it |
| `PIPESOR_DATABASE_URL`, `_USERNAME`, `_PASSWORD` (local only) | PostgreSQL database `pipesor` |
| `PIPESOR_SERVICE_BUS_CONNECTION_STRING` (local) or `_NAMESPACE`, `_TOPIC` (`workflow-evidence`), `_SUBSCRIPTION` (`pipeline`) | snapshot receiver |
| `PIPESOR_DASHBOARD_ORIGIN` | CORS origin for `/api/v1/**` (no credentials mode) |
| `SERVER_ADDRESS`, `SERVER_PORT` | default `127.0.0.1:8080`; the image sets `0.0.0.0` |

Limits with defaults (override with Spring relaxed binding, e.g. `PIPELINE_INGESTION_REQUEST_TIMEOUT`):
`pipeline.ingestion.*` (connect 2 s, request 5 s, 256 KiB responses, 50 repositories per verification),
`pipeline.capture-scope.*` (`reconcile-enabled`, `reconcile-interval` 5 min, `page-size` 100; the
first two also read the `PIPESOR_CAPTURE_SCOPE_*` variables above), `pipeline.owner-validation.*` (3 s, 4 KiB requests, 1 KiB peer responses, 16 in flight),
`pipeline.messaging.*` (`enabled`, 256 KiB messages, concurrency 4, lock renewal 5 min, drain 30 s,
`max-deliveries` 10). `server.tomcat.connection-timeout` is 10 s.

## Health and migrations

`/actuator/health/liveness` and `/actuator/health/readiness` are the only actuator routes
(no details). Readiness includes the database and, when the receiver is enabled, the Service Bus
processor state. It also contributes `captureScope`, which is always UP and carries the last
reconciliation pass only as an internal detail: an unreachable Ingestion never makes Pipeline unready. Flyway runs at startup on `ops` (history `ops.pipeline_flyway_schema_history`);
`V6` creates `audit.audit_events` and its enum if absent (Pipeline is the MVP owner of that table).
Hibernate uses `ddl-auto: validate`.

## Routes

| Route | Caller |
|---|---|
| `GET /.well-known/jwks.json`, `GET /actuator/health/{liveness,readiness}` | unauthenticated |
| `/api/v1/**` | session token with scope `Pipeline.Access`, then ADR-020 grant and current GitHub access per resource |
| `POST /internal/v1/evidence/operational-reference-checks` | workload `correlation` with `Correlation.OperationalReferences.Check`, or workload `log-event` with `Evidence.Check` |
| `POST /internal/v1/diagnosis-read-entitlements` | workload `recommendation` with `Recommendation.DiagnosisEntitlements.Resolve` plus `X-Pipesor-Subject-Token` |
| `PUT /internal/v1/incidents/{incidentId}/diagnosis-summary` | workload `recommendation` with `Recommendation.DiagnosisSummaries.Write` (no subject token, ADR-027) |

Everything else is denied. Missing or invalid tokens return 401, a valid token without the
route's caller, role or scope returns 403; both are `application/problem+json`
(`code` `UNAUTHENTICATED` / `ACCESS_DENIED`, `correlationId`) with `Cache-Control: no-store`.
A subject token is accepted only next to a workload token; it is validated as a session token for
this audience. Other errors: `400 INVALID_REQUEST`, `403 GITHUB_CONNECTION_REQUIRED`,
`404 NOT_FOUND`, `409 CONFLICT`, `503 GITHUB_VERIFICATION_UNAVAILABLE`, `500 INTERNAL_ERROR`;
exception messages are never returned.

### Incident queue

`GET /api/v1/incidents?state=OPEN|RESOLVED&pageSize=1..100 (default 24)&pageToken=` plus the optional,
repeatable filters of [ADR-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-027-incident-queue-read-model.md):
`pipelineId`, `repositoryId` (GitHub id), `severity` (`CRITICAL|HIGH|MEDIUM|LOW|UNCLASSIFIED`), `stage`,
`status` (only those of the state), `diagnosis` (`PENDING|CONFIRMED|CORRECTED|REJECTED|ABSENT`) and `q`
(`INC-123`/`123` matches `displayNumber`; other text, 2–100 characters, matches the repository full name
or workflow name case-insensitively). Values of one filter are OR-ed, filters are AND-ed. The exact
contract is in [MVP HTTP API v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/mvp-http-api-v1.md#incident-queue-filters).

`state` is required: `OPEN` covers every active incident (`OPEN`, `IN_REMEDIATION` and
`VERIFICATION`), `RESOLVED` covers `RESOLVED`. Candidates are incidents of pipelines on which the
caller holds any grant and whose repository current GitHub facts keep readable and admitted; a
`repositoryId` filter is intersected with that set, and every filter is an SQL predicate of the same
statement, before paging and counting. Order is `createdAt` descending, then id. Response:
`{"items": [IncidentView...], "nextPageToken": "opaque" | null, "total": n, "appliedFilters": {...}}`.
The page and `total` are read in one repeatable-read snapshot. Page tokens (`q2`) carry a digest of the
normalized state and filters; another listing's token, or a malformed one, is `400 INVALID_PAGE_TOKEN`
(unbound `q1` tokens are still accepted without filters). Invalid filter values are `400 INVALID_FILTER`.

Every incident read (`/incidents`, `/incidents/{id}`, `/pipelines/{id}/incidents`) adds `repository
{repositoryId, fullName}`, `workflow {workflowId, name}` and `diagnosis {status, probableCause,
createdAt} | null` from `ops.incident_diagnoses`.

`GET /api/v1/incidents/summary` takes the same `state` and filters and returns `total`, `bySeverity`,
`byStatus` (statuses of the state) and `byDiagnosis`, each summing to `total`.
`GET /api/v1/insights?from=&to=&top=` computes History Insights over visible incidents resolved in the
window (default: the last 30 UTC calendar days; at most 366 days; `top` 1–50, default 10).

Indexes (`V9`): `incidents (status, created_at, id)`, `(pipeline_id, status, created_at, id)`,
`(severity, status)`, `(stage, status)`, `resolved_at` for resolved incidents, grants by
`(principal_ref, pipeline_id)` and `incident_diagnoses (status)`. The text query uses `ILIKE` over the
small repository and pipeline tables (no trigram extension).

### Diagnosis summary projection (ADR-027)

`PUT /internal/v1/incidents/{incidentId}/diagnosis-summary` stores Recommendation's current summary
(`diagnosisId`, `version`, `executionId`, `pipelineId`, `githubRepositoryId`, `status`,
`reviewRevision`, `reviewedAt`, `probableCause` ≤ 1000 characters, `createdAt`; unknown fields
rejected) in `ops.incident_diagnoses`. It is applied only when the execution is the incident's ORIGIN
execution and the pipeline and repository are the incident's (`409 SCOPE_MISMATCH` otherwise;
`404 INCIDENT_NOT_FOUND`; `400 INVALID_REQUEST`). Writes are monotonic on `(version,
reviewRevision)`; `200` echoes the summary with `applied: false` when a same-or-later one is stored.
No row means `ABSENT`. Recommendation feeds it from a transactional outbox, so the queue converges
after outages.

### Diagnosis read entitlement

`POST /internal/v1/diagnosis-read-entitlements` — request `{"incidentId": "uuid"}` (unknown fields
rejected). The subject is the subject token's session (`tid:oid`), never a body field. Pipeline
checks the subject's grant on the incident's pipeline plus current GitHub access through Ingestion
(ADR-020), then reads the incident's single failed ORIGIN execution.

`200` (`Cache-Control: no-store`):

```json
{
  "incidentId": "uuid",
  "pipelineId": "uuid",
  "executionId": "uuid",
  "githubInstallationId": "string",
  "githubRepositoryId": "string",
  "tenantId": "uuid",
  "capabilities": ["VIEW"]
}
```

`executionId` is the ORIGIN execution. `capabilities` is `["VIEW"]` or `["VIEW", "OPERATE"]`:
the subject's effective incident capabilities after the grant and GitHub checks.
`404 INCIDENT_NOT_FOUND` is returned both for an absent incident and for a subject without VIEW
(including a missing GitHub connection), so existence is not revealed. `503` means GitHub
verification is unavailable; Recommendation treats any status other than 200/403/404 as
authority unavailable.

### Operational reference check

Contract unchanged from the [owner validation increment](deployment-contract.md#internal-evidence-owner-validation-v1):
JSON request with the capture binding and deterministic ids, `X-Correlation-Id` equal to the body
`correlationId`, response `{requestId, correlationId, decision}` with `Cache-Control: no-store`.
The check calls Ingestion `POST /internal/v1/capture/machine-scope-checks` with a workload token
(`Pipeline.MachineScope.Check`). The request body is read before admission, so a slow upload never
holds a processing slot; `413`, `415`, `429` and `503` keep their previous meaning.

## Outbound calls

| Target | Route | Token |
|---|---|---|
| Ingestion | `POST /internal/v1/github/access-verifications`, `POST /internal/v1/github/workflow-verifications` | workload token (`Pipeline.GitHubFacts.Read`) + the current session as `X-Pipesor-Subject-Token` |
| Ingestion | `POST /internal/v1/capture/machine-scope-checks` | workload token (`Pipeline.MachineScope.Check`) |
| Ingestion | `PUT /internal/v1/capture/workflow-scopes/{githubRepositoryId}/{githubWorkflowId}` | workload token (`Pipeline.CaptureScope.Write`), no subject token |

GitHub verification maps Ingestion `409` to `GITHUB_CONNECTION_REQUIRED`, `403` to denial and any
other failure, timeout or binding mismatch to 503. Selections larger than the batch size are split;
an empty selection discloses nothing and is not sent. Results are reused only within one request.

### Capture workflow scopes (ADR-023)

Pipeline drives Ingestion's capture scope for each registered workflow with
`{"requestId", "correlationId", "pipelineId", "monitoringStatus": "ACTIVE|PAUSED"}` and
`X-Correlation-ID`. A `200` must echo `repositoryId`, `workflowId`, `pipelineId` and `status` and carry
`scopeRevision` and `revision`; otherwise the result is invalid. `404 REPOSITORY_NOT_ADMITTED`,
`409 PIPELINE_MISMATCH`, `400`, `401/403`, `5xx`, timeouts and oversized responses each map to a
named outcome and are logged with a fixed reason and the correlation id, never with payloads or tokens.
Redirects are not followed; the timeout and response limit are `pipeline.ingestion.request-timeout`
and `max-response-bytes`.

- After `POST /api/v1/pipelines` commits, and after a settings/policy update commits with a changed
  `monitoringStatus`, the committed status is sent outside the SQL transaction. A failed sync never
  fails or rolls back the committed response; it is logged and left to reconciliation.
- Reconciliation runs at application readiness and then every `reconcile-interval` (fixed delay, one
  pass at a time per instance). It reads all pipelines in pages of `page-size` (each page its own short
  read) and repeats each current status. The PUT is idempotent, so replicas running concurrently
  converge. A pass stops at the first unavailable outcome and the next pass starts over; rejections
  such as `REPOSITORY_NOT_ADMITTED` are counted and the pass continues.

## Snapshot receiver

Subscription `pipeline` of topic `workflow-evidence` (`eventType = 'execution.snapshot'`).
`SnapshotEnvelope` checks application properties `eventType`/`schemaMajor` (1), `messageId == eventId`
and the broker correlation id against the payload; a mismatch is dead-lettered with its reason.
Permanent rejections are dead-lettered; `UNKNOWN_WORKFLOW` and other retryable outcomes are
abandoned for redelivery and only become `RETRY_EXHAUSTED` at the subscription's delivery limit.
Processor errors are logged with their exception.
