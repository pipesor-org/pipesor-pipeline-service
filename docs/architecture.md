# Architecture consumption

Consulted on 2026-11-06: `pipesor-org/pipesor-docs` full Git SHA **`c84ef3e4629fcb0c38315f32b641a5fb949e450d`**, using the clean local checkout (no uncommitted source overlay). Canonical model: `ey_aiops_ci_cd_diagnosis`, version `0.5.0-mvp-iac-alignment`, status `draft`. `pipeline_service` is a confirmed container with an independent repository under ADR-018; confirmed is a model status only.

Architecture source links below follow `main`. The summary reflects the consulted revision recorded above; review it against subsequent accepted decisions before implementation.

See the [documentation baseline](documentation-baseline.md) for the remote reconciliation and adoption limits of this update. Source SHAs are evidence identifiers, not frozen navigation links.

## Sources by subject

- [Source instructions](https://github.com/pipesor-org/pipesor-docs/blob/main/AGENTS.md), [architecture model](https://github.com/pipesor-org/pipesor-docs/blob/main/model/architecture.yml): `elements[pipeline_service]`, `relationships`, `deployment.container_hosts.pipeline_service`, `architectural_invariants` (INV-001/002/003/005/006/007/008/009/010), CTRL-001–006, `audit_trail`, `retention`.
- [Persistent model](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain.yml), [domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md): table ownership, assumptions, retention and open questions. Domain owners are listed in the delivery contract; no derived SQL/DBML is consumed as independent authority.
- Applicable ADRs: [ADR-001](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-001-azure-container-apps.md), [ADR-002](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-002-azure-observability.md), [ADR-013](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-013-retention-365-days.md), [ADR-018](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-018-application-architecture-ddd-cqrs.md), [ADR-003](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-003-identity-api-perimeter.md), [ADR-006](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-006-dashboard-api-contract.md), [ADR-016](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-016-identity-api-human-review-and-artifact-flow.md). [ADR-019](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-019-align-mvp-with-iac.md) supersedes the affected historical APIM/Blob/foundation statements. For current decisions, read the [decision index](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/index.md) for subsequent supersession.
- [Proposed design](https://github.com/pipesor-org/pipesor-docs/blob/main/model/design.yml), [design rationale](https://github.com/pipesor-org/pipesor-docs/blob/main/model/design_decisions.md): retain proposed states and DCQ questions; not approved classes or API contracts.
- [Source provenance](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/sources/README.md), [reconciliation](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/reconciliation.md), [committed pending register](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). Charter controls scope; architecture/ADRs control boundaries; drift/applicable technology ADRs control technology. Functional research is derived and its API v0.1 is proposed.

## Current MVP alignment

[ADR-019](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-019-align-mvp-with-iac.md) incorporates the accepted IaC direction from `pipesor-iac@4b42c0c8e66578dfb890ae1cca8c9c31c774026e`: [native ingress](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-003-mvp-native-ingress.md), [resource baseline](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/mvp-resource-baseline.md) and [dev preparation](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-004-mvp-state-and-deployment.md).

- Use ACA native ingress for authorized dashboard/workflow calls. APIM and replacement gateways are excluded from the MVP. Internal services remain internal; Entra authentication, service authorization, scopes and trace/audit propagation still require implementation and contracts.
- Exclude workload Blob. GitHub Actions remains the original log source; PostgreSQL holds sanitized evidence, metadata, excerpts and knowledge. Preserve provenance and generic references without inventing replacement locators or duplicating complete logs. Independent recovery and 365-day retention remain unresolved (PD-017/030/031). Terraform state Blob is separate.
- Use the Microsoft Foundry AIServices account/project foundation with public HTTPS and Entra-only authentication. Preserve the logical inference capability and PostgreSQL/pgvector ownership. Application endpoint/SDK, models, embeddings/dimension, geography, grants and corpus remain pending (PD-005/006/023/026/027).
- Terraform retains four isolated environment roots/states; only `dev` is selected for the temporary academic MVP. Services must be implemented, integrated locally and validated before separately authorized Azure actions. IaC preparation is not deployment evidence; future promotion rules remain applicable outside the dev exception.

PD-033–036 and the stage-5 report are committed at the consulted revision. The old uncommitted overlay description is superseded. The pending register identifies Jhon Galvez and Ramiro Guzman as contract approval authority; approval count, GitHub enforcement and actual delivery-contract approvals remain unspecified.

## Repository boundary inventory

ADR-018 specifies multirepo per deployable microservice. Repository names below apply the existing `pipesor-` prefix and kebab-case to canonical IDs; IDs are unchanged.

| Canonical container | Independent backend repository |
|---|---|
| `ingestion_normalization_service` | `pipesor-ingestion-normalization-service` |
| `pipeline_service` | `pipesor-pipeline-service` |
| `log_event_service` | `pipesor-log-event-service` |
| `evidence_correlation_engine` | `pipesor-evidence-correlation-engine` |
| `recommendation_service` | `pipesor-recommendation-service` |
| `nlp_log_processor` | `pipesor-nlp-log-processor` |
| `rag_retrieval` | `pipesor-rag-retrieval` |

`web_dashboard` already has `pipesor-frontend`. Managed Service Bus, PostgreSQL/pgvector, Entra ID and Microsoft Foundry are not custom backend service repositories. IaC already has `pipesor-iac`. `pretrained_llm_diagnostic_service` is an external managed service despite its ID (ADR-015). The diagnostic API, orchestrator, output composer and human review are internal to `recommendation_service` (ADR-016/017). Audit is cross-cutting (ADR-011); reporting was removed (ADR-009), detector/LogBERT are outside MVP (ADR-010), notifications are excluded (ADR-012), and direct scanner integrations are outside MVP (ADR-014). No repository is created for those capabilities.

No ambiguity blocks the seven service boundaries. Operational gaps remain: embedding access paths are not separately declared for NLP/RAG despite the documented provider, and the owners/mechanisms for configuration, migrations and promotion are incomplete. Those gaps do not authorize new relationships or services.

## Service relationships

Read these canonical IDs in `model/architecture.yml`; internal C4 projections do not add deployment boundaries:

- `rel_service_bus_pipeline_service`: `azure_service_bus` → `pipeline_service` (Azure Service Bus).
- `rel_pipeline_service_postgresql`: `pipeline_service` → `postgresql_operational_store` (PostgreSQL).
- `rel_dashboard_pipeline_service`: `web_dashboard` → `pipeline_service` (REST/HTTPS).

## Pipeline implementation context

The service receives execution events through `rel_service_bus_pipeline_service`, persists its owned operational data through `rel_pipeline_service_postgresql`, and serves dashboard queries through `rel_dashboard_pipeline_service`. These are the existing integration boundaries; the source model does not specify complete event payloads, API shapes or state-transition rules.

The [data lifecycle](deployment-contract.md#data-lifecycle) records owned tables and inferred grants. Shared PostgreSQL infrastructure does not imply shared write ownership. `ops.pipeline_access_grants` remains inferred; the registration and access policies are pending under PD-007/008. Logical references and independently arriving data need the agreement tracked by PD-031/033, rather than assumed cross-service ordering.

The [pending-decision register](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md) tracks the integration gaps: PD-003 concerns incident-severity derivation; PD-015 concerns topology approval evidence; PD-033 concerns the event contract and delivery semantics; PD-034 concerns REST exchanges; PD-035 concerns delivery to IaC. Each is a dependency of the increment that uses it, not a prohibition on unrelated local implementation. Proposed class/API examples do not close these gaps.

## Organization conventions consulted

Shared policy checkout/remote `main`: `pipesor-org/.github@36691787bb9d0c9fd6b78c76bfe82adffa0c1c87`; [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md), [PR template](https://github.com/pipesor-org/.github/blob/main/.github/PULL_REQUEST_TEMPLATE.md). Reuse organization defaults by reference, without duplicated community files or an unapproved license. Contribution rules remain owned by that policy; the baseline audit verified that the service repository is private, uses `main` and permits squash merges only. Existing Bun/Lefthook tooling belongs to those repositories; no application/toolchain dependency is introduced here.

Missing source approvals/provenance are tracked in PD-012–024, including individual ADR approval records (PD-014); preserve Accepted status while reporting those limits. Unresolved decisions relevant to this service: [PD-035](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-032](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-003](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-007](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-008](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-015](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-031](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-033](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-034](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). Scope them to the affected integration or deployment rather than blocking repository initialization.

## GitHub enforcement limitation

The baseline audit confirmed private visibility, `main` as default branch and squash-only merge settings. The GitHub rulesets API returned HTTP 403 with a plan-upgrade requirement for this service; no enforced branch protection or independent approval gate is certified. PR-title/body squash formatting and automatic branch deletion were historical initialization settings, not independently revalidated by this audit. Mandatory reviewed PRs and no direct pushes to main remain contribution rules; server-side enforcement has not been established. Keep these repositories private; do not change visibility to work around that limitation. No empty CI checks or deployment workflows are added.

## First implementation agreement

Session-authorized on 2026-11-06; revisable development conventions, not evidence of implementation or contract approval. Follow the [common backend conventions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/backend-conventions.md) and [Java/Spring conventions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/java-spring-conventions.md). The source baseline above remains historical; these local changes must not be described as integrated into main before merge.

One Maven project with wrapper, organized by functionality:

| Package | Responsibility |
|---|---|
| catalog | GitHub repositories and monitored workflows. |
| execution | Executions, attempts and stages. |
| incident | Incidents and origin/verification execution links. |
| shared | Minimal locally reused domain and persistence base classes. |
| configuration | Technical configuration and dependency composition. |

Within each functionality use domain/model (aggregates, entities, valueobjects), domain/services for business operations, application/commands, application/queries, application/services for use-case contracts, application/commandhandlers, application/queryhandlers, application/ports, interfaces/rest (resources, transform), interfaces/messaging (transform), and infrastructure/persistence/jpa as needed. These packages do not establish aggregate boundaries or new bounded contexts. REST belongs inside interfaces. Both domain and application remain framework-free; configuration composes handlers and adapters. This user-authorized Clean Architecture refinement supersedes the initial placement of command/query records and use-case contracts in domain. Transformers translate representations without owning business rules.

Use Spring Data JPA/Hibernate with separate persistence models, explicit ops table names and standard snake_case naming, Flyway SQL migrations, and Hibernate schema validation. Use UUID identity, Instant times and minimal separate domain/JPA bases. Document REST with springdoc-openapi. Exact dependencies remain subject to compatibility verification and pinning when the Maven project is introduced.

The current persistent model has created_at but no updated_at. Before implementing mutable timestamp bases or migrations containing updated_at, update the applicable source-model tables and domain decision, then regenerate and validate affected derivatives. Do not add it implicitly or to append-only records. Functional audit remains distinct and its write mechanics unresolved.

Service Bus provides the asynchronous input. Handlers alone do not imply thread dispatch or deferred REST execution; queries return directly by default. Settle message delivery, commit coordination, duplicates, concurrency and ordering under PD-033 before integration. Keep the documented incident plus failed ORIGIN invariant atomic; preserve all domain constraints in application and persistence.

Implement small complete increments with acceptance criteria, relevant failure tests and current README commands. First establish the build, local startup and test tooling, then implement a use case whose required semantics are agreed. No empty package scaffolding, generic bus, shared library or cloud deployment is required by this agreement. PD-003/007/008/031/033/034/035 remain scoped prerequisites for the increments that use them.

## Implemented internal repository query

The [second controlled increment](implementation-catalog-query.md) implements GetRepositoryByIdQuery → RepositoryQueryService/application handler → RepositoryLookup port → JPA adapter → ops.repositories. The adapter transforms persistence data into a framework-free GitHubRepository domain entity. Unknown UUID returns Optional.empty; external IDs remain text and nullable default_branch is preserved.

Minimal bases separate domain UUID identity from JPA identity/creation metadata. This increment reads existing creation times; allocation and auditing of new records remain part of the first write use case. Flyway owns the first table migration; Hibernate validates its names/types without updating the schema. No REST/business path, command handler, registration/access policy, lifecycle transition or broker contract is created by this internal read slice.

## Clean Architecture refinement

User-authorized after review on 2026-11-06. GetRepositoryByIdQuery and RepositoryQueryService belong to application/queries and application/services. Domain services are reserved for domain business operations; use-case coordination is not a domain service. CatalogConfiguration registers the plain Java handler with constructor dependencies. The read-only transaction wraps JpaRepositoryLookup, including transformation, so application has no Spring/ORM imports. A future multi-write command must retain a use-case-wide transaction through infrastructure composition; per-adapter transactions must not split its atomicity.

RepositoryLookup is an application output port for reads. JpaRepositoryLookup implements it; SpringDataGitHubRepository is the technical Spring Data interface using GitHubRepositoryJpaEntity. These technical names do not define a DDD aggregate repository. The SQL mapping remains ops.repositories, so no migration change is required.

An entity has stable identity; an aggregate defines a consistency boundary and exposes mutations through its root. A table, foreign key or base class does not define that boundary. Document the first command's invariants and transaction scope before introducing its aggregate; an aggregate root is also an entity and needs no empty base class. Value objects must add domain meaning, validation or prevent actual identifier mixups. UUID and Instant remain suitable value types for the current read; do not add custom wrappers without a concrete requirement. Queries may return projections without loading aggregates.

The shared convention refinement is published in [pipesor-docs PR #7](https://github.com/pipesor-org/pipesor-docs/pull/7), candidate 752bdcfea69c235775e9560a366d343c2bb2d959, pending review/integration. Merge that convention before the dependent implementation PR. The source revision recorded for increment 2 remains historical; a candidate commit is not a merged baseline.

## Incident domain and identity rules

Session-approved local domain implementation: Incident is an entity and aggregate root protecting its immutable failed ORIGIN from the same pipeline. ExecutionOutcome identifies that execution without owning a mutable execution entity. IncidentDetails, IncidentLifecycle and VerificationExecution carry domain values; equality uses UUID value equality.

The root enforces OPEN → IN_REMEDIATION → VERIFICATION → RESOLVED, requires a subsequent linked successful verification plus explicit human resolution, permits return from failed verification to remediation and forbids reopening RESOLVED. Verification may reference another authorized pipeline; authorization and atomic persistence belong to the future application adapters. No diagnosis/evidence state is changed. This commit provides domain behavior only, with no public endpoints or durable incident writes.

DeterministicIds applies the session-approved UUIDv5 URL-namespace names for new integrated pipelines, executions/attempts and automatic incidents. Repository/external GitHub identifiers retain their documented types. No existing UUID is rewritten. Native catalog/stage/status enums express the approved model; they add no tables or user/role store.

## Contrato de evento normalizado v1 aprobado en sesion

Propuesta solicitada y luego **aprobada explícitamente por el usuario en esta sesión**, junto con la estrategia UUIDv5. El consumidor local ya está implementado; su activación e integración real con los otros servicios/Azure no se han validado. Alcance: entrada de Pipeline por las relaciones existentes ingestion → Service Bus → Pipeline, conservando el topic y suscripciones documentados. La aceptación conjunta con los demás consumidores sigue PD-031/033; este borrador no crea un productor nuevo ni una publicación de salida desde Pipeline.

Usar snapshots completos de una ejecución/intento, no parches. Conservar el contexto canónico pipeline, execution, commit, service, environment y correlationId. IDs externos de GitHub son texto; runAttempt es entero positivo. Las fechas son UTC RFC 3339 con zona; valores ausentes se representan con null, sin borrar campos a partir de un parche implícito.

```json
{
  "schemaVersion": "1.0",
  "eventId": "b8d60b32-fefa-443f-af08-26a1d449c655",
  "eventType": "execution.snapshot",
  "source": "github_actions",
  "occurredAt": "2026-11-06T18:01:03Z",
  "sourceUpdatedAt": "2026-11-06T18:01:02Z",
  "correlationId": "ci-observation-482",
  "pipeline": {"githubRepositoryId": "12345678901234567890", "githubWorkflowId": "43210"},
  "execution": {
    "githubRunId": "98765432109876543210", "runAttempt": 2,
    "branch": "main", "status": "completed", "conclusion": "failure",
    "startedAt": "2026-11-06T18:00:00Z", "completedAt": "2026-11-06T18:01:02Z"
  },
  "commit": {"sha": "0123456789abcdef0123456789abcdef01234567"},
  "service": null,
  "environment": null,
  "stages": [{"stage": "TESTING", "status": "completed", "startedAt": null, "completedAt": null}],
  "failure": {"stage": "TESTING", "summary": "Sanitized test failure", "detectedAt": "2026-11-06T18:01:02Z"}
}
```

The producer obtains run identity/attempt/status from the [GitHub workflow-run resource](https://docs.github.com/en/rest/actions/workflow-runs); sourceUpdatedAt describes the upstream snapshot, not broker receipt time. Source timestamps can collide, so equal time alone must never choose a winner. Only supported normalized stages are accepted; raw logs, credentials, arbitrary GitHub payloads and embedded diagnoses are excluded.

| Decision accepted in this session | Concrete behavior |
|---|---|
| Known workflow | Resolve repository/workflow to a preregistered pipeline. Unknown workflow: dead-letter with reason UNKNOWN_WORKFLOW; no implicit registration. |
| Monitoring paused | Acknowledge without updating execution/incident state. Resuming does not implicitly replay skipped observations; replay is an explicit operational action. |
| Failure classification | Only completed failure/timed_out creates an incident; cancelled, skipped and neutral do not. Failed snapshots require sanitized failure.stage/summary/detectedAt. Stage classification belongs to normalization, never an invented default stage. Severity remains unset pending PD-003. |
| Duplicate delivery | Persist receipt identity and canonical payload digest transactionally. Same eventId/same digest is a no-op; same eventId/different digest is a conflict to DLQ. A new eventId describing an identical snapshot is also a no-op. |
| Out-of-order snapshots | Persist sourceUpdatedAt plus canonical content digest per execution attempt. Older snapshots are acknowledged without replacing newer state; equal timestamp/different content is dead-lettered for review; newer snapshots may advance state but may not regress completed to nonterminal or change a terminal conclusion silently. Contradictions go to DLQ. |
| Incident multiplicity | One automatically opened incident per failed execution attempt, protected by its deterministic incident UUID/primary key and transaction. This session-approved automatic creation policy is stricter than the integrated DD-002 multiplicity; the older trusted internal creation command still permits multiple incidents from one origin and is not a public endpoint. Human resolution remains explicit even if a later run succeeds. |
| Settlement | Commit receipt, operational writes and automatic incident creation together, then acknowledge. Transient database/lock/transport failure abandons for bounded retry; maxDeliveryCount=10 then DLQ (the adapter also dead-letters an exhausted transient failure on delivery 10). Permanent malformed/version/conflict failures go to DLQ with safe reason metadata. No exactly-once claim or in-memory deduplication. |
| Compatibility | Reject unsupported major versions. Additive optional fields within v1 are tolerated but ignored; required fields and business semantics cannot change silently. TTL, lock duration, concurrency and payload-size limits remain part of broker delivery configuration before activation. |

**PD-031 identity rule explicitly accepted by the user in this session:** preserve owner assignment using a public deterministic UUIDv5 naming rule so independently arriving consumers can reference the same IDs without new synchronous service relationships. Use the standard URL namespace and canonical names urn:pipesor:v1:pipeline:github:{repositoryId}:{workflowId}, urn:pipesor:v1:execution:github:{repositoryId}:{runId}:{runAttempt} and urn:pipesor:v1:incident:{executionUuid}. Only owner services persist their objects; normalization/other consumers may compute logical references. Canonical decimal external IDs have no sign/leading zeroes; executionUuid uses lowercase canonical UUID form. The user approved naming and the one-automatic-incident policy; integration with other consumers and formal reconciliation of source models remain pending; existing locally created UUIDv4 incidents are not rewritten. Independently persisted references may precede the owner row; recovery/reconciliation of rejected upstream events remains a joint consumer contract, not a cross-schema FK.

Local implementation: ExecutionSnapshotTransformer validates typed input, rejects duplicate JSON keys and fractional attempts, canonicalizes stage order/commit case and computes SHA-256 message/content digests without trusting producer hashes. ApplyExecutionSnapshotCommandHandler owns the delivery decisions; SQL/JPA adapters perform the atomic writes. V5 adds ops.execution_event_receipts as technical persistence for the approved durable receipt/order policy, not a new business aggregate. It stores hashes/metadata, never the payload. PostgreSQL microseconds plus a submicrosecond remainder preserve exact source-version ordering. Identical newer content advances the watermark without rewriting operational data. Existing stage identities/creation times and already known stage rows are retained; omitted stages are not deleted as historical observations.

Message-ID advisory locks serialize conflicting reuse; workflow locks serialize first observations, including paused/configuration decisions; an execution-identity lock also protects simultaneous messages assigning one run to different workflows. The handler rejects any attempt to reassign a stored run to another workflow or external identity. No exactly-once or global broker-order guarantee is claimed. Source timestamps/canonical content, rather than broker arrival time, decide replacement.

UUIDv4 rows remain untouched. If an existing registration or execution uses an identity incompatible with the newly accepted deterministic rule, the consumer rejects it with LEGACY_ID_MAPPING_REQUIRED; an explicit alias/migration decision is required before integrating that legacy dataset. No such production dataset was inspected or migrated. Technical receipts have no automatic purge policy; deleting them would weaken durable deduplication/order protection.

ServiceBusConfiguration is disabled unless pipeline.messaging.enabled=true. When explicitly configured, it uses system-assigned managed identity, a topic subscription, manual settlement, no prefetch, explicit concurrency/renewal/drain limits and safe error reasons without payload logging. Local tests replace only SDK message contexts; no Azure namespace or consumer was contacted. Topic/subscription values, scoped grants, delivery/entity settings and target authorization are still required for activation. This local approval record does not silently close the integrated PD-031/033 registers or update other consumers.
