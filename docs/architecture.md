# Architecture consumption

Current approved evolution: [ADR-020 — GitHub user access and workflow onboarding](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-020-github-user-access-and-workflow-onboarding.md), user-approved on 2026-11-06 and now integrated in pipesor-docs main. Current source consultation for local delivery preparation (2026-11-08): `e22627cfee10b0b02edab084b98eb18926c678b5`. The later local capture/publication draft in `model/domain_decisions.md` belongs to Ingestion and is not treated as an accepted CI/CD decision. See [approved GitHub access evolution](#approved-github-access-evolution) for service impact and the remote integration gap.

Historical consultation on 2026-11-06: `pipesor-org/pipesor-docs` full Git SHA **`c84ef3e4629fcb0c38315f32b641a5fb949e450d`**, using the then-clean local checkout (no uncommitted source overlay at that historical cut). Canonical model: `ey_aiops_ci_cd_diagnosis`, version `0.5.0-mvp-iac-alignment`, status `draft`. `pipeline_service` is a confirmed container with an independent repository under ADR-018; confirmed is a model status only.

The historical implementation approvals below remain valid within their recorded scope. Current delivery work adds local CI/container tooling and an explicitly disabled ACA adapter; it does not approve Azure activation, D03/D03-EXP, a new deployment ownership standard or changes to upstream ADRs.

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

The [shared CI/CD promotion TO-BE](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md#future-environment-promotion-to-be) defines the future environment/qualification contract separately from the current dev-only execution footprint. It is a shared design target, not activation or amendment of upstream ADRs.

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

The service receives execution events through `rel_service_bus_pipeline_service`, persists its owned operational data through `rel_pipeline_service_postgresql`, and serves dashboard queries through `rel_dashboard_pipeline_service`. These are the original integration boundaries; ADR-020 adds the internal GitHub access boundary below. The source model does not specify complete event payloads, API shapes or state-transition rules.

The [data lifecycle](deployment-contract.md#data-lifecycle) records owned tables and inferred grants. Shared PostgreSQL infrastructure does not imply shared write ownership. `ops.pipeline_access_grants` remains inferred in the integrated source model. The session approval below now defines functional capabilities and authorizes their local storage; ADR-020 now approves the MVP registration/access policy, while registration implementation, external identity integration and physical representation remain pending under PD-007/008/027/034/035. This does not silently promote the source-model status. Logical references and independently arriving data need the agreement tracked by PD-031/033, rather than assumed cross-service ordering.

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

The current persistent model has created_at but no updated_at. Before implementing mutable timestamp bases or migrations containing updated_at, update the applicable source-model tables and domain decision, then regenerate and validate affected derivatives. Do not add it implicitly or to append-only records. Functional audit remains distinct; the implemented local transaction and the unresolved shared-schema delivery ownership are described below.

Service Bus provides the asynchronous input. Handlers alone do not imply thread dispatch or deferred REST execution; queries return directly by default. Settle message delivery, commit coordination, duplicates, concurrency and ordering under PD-033 before integration. Keep the documented incident plus failed ORIGIN invariant atomic; preserve all domain constraints in application and persistence.

Implement small complete increments with acceptance criteria, relevant failure tests and current README commands. First establish the build, local startup and test tooling, then implement a use case whose required semantics are agreed. No empty package scaffolding, generic bus, shared library or cloud deployment is required by this agreement. PD-003/007/008/031/033/034/035 remain scoped prerequisites for the increments that use them.

## Implemented internal repository query

The [second controlled increment](implementation-catalog-query.md) implements GetRepositoryByIdQuery → RepositoryQueryService/application handler → RepositoryLookup port → JPA adapter → ops.repositories. The adapter transforms persistence data into a framework-free GitHubRepository domain entity. Unknown UUID returns Optional.empty; external IDs remain text and nullable default_branch is preserved.

Minimal bases separate domain UUID identity from JPA identity/creation metadata. The original read slice remains available; the current write use cases below allocate owner UUIDs using UUID.randomUUID and creation timestamps from an injected UTC Clock truncated to PostgreSQL microseconds. Flyway owns schema evolution; Hibernate validates mappings without updating the schema.

## Clean Architecture refinement

User-authorized after review on 2026-11-06. GetRepositoryByIdQuery and RepositoryQueryService belong to application/queries and application/services. Domain services are reserved for domain business operations; use-case coordination is not a domain service. CatalogConfiguration registers the plain Java handler with constructor dependencies. The read-only transaction wraps JpaRepositoryLookup, including transformation, so application has no Spring/ORM imports. A future multi-write command must retain a use-case-wide transaction through infrastructure composition; per-adapter transactions must not split its atomicity.

RepositoryLookup is an application output port for reads. JpaRepositoryLookup implements it; SpringDataGitHubRepository is the technical Spring Data interface using GitHubRepositoryJpaEntity. These technical names do not define a DDD aggregate repository. The SQL mapping remains ops.repositories, so no migration change is required.

An entity has stable identity; an aggregate defines a consistency boundary and exposes mutations through its root. A table, foreign key or base class does not define that boundary. Document the first command's invariants and transaction scope before introducing its aggregate; an aggregate root is also an entity and needs no empty base class. Value objects must add domain meaning, validation or prevent actual identifier mixups. UUID and Instant remain suitable value types for the current read; do not add custom wrappers without a concrete requirement. Queries may return projections without loading aggregates.

The shared convention refinement was integrated by [pipesor-docs PR #7](https://github.com/pipesor-org/pipesor-docs/pull/7) at 18dacfbfcee22a92bb98dba85a378b023e96ac70. The first implementation was integrated by [Pipeline PR #4](https://github.com/pipesor-org/pipesor-pipeline-service/pull/4) at 60b8a3a5ea902a48a86699e94633f035292213bf. Earlier source/candidate revisions in the increment records remain historical; use the actual merge revisions as the next increment baseline.

## Implemented operational core

Local implementation on this feature branch, based on pipesor-docs main at 18dacfbfcee22a92bb98dba85a378b023e96ac70, plus the user's explicit functional approvals in this session (2026-11-06). No neighboring repository, model status or pending-decision register is changed implicitly.

- V1 creates repositories. V2 preserves the confirmed source fields, native enums, unique/check constraints and intra-ops foreign keys for pipelines, executions, stages, incidents and incident runs. V3 preserves DD-002's failed, same-pipeline, exactly-one-ORIGIN constraint through deferred database triggers, including protection against origin removal and TRUNCATE. V4 adopts the source's provisional grant storage for the session-approved capability policy.
- PipelineQueryService, ExecutionQueryService and IncidentQueryService return framework-free projections, with parent filters, stable createdAt/id ordering and bounded offset pages (1–100). Stages and incident runs are separate reads. These trusted internal services are not externally authenticated interfaces.
- OpenIncidentCommandHandler locks an authoritative failed execution and creates Incident plus ORIGIN in one transaction. There is no deduplication/correlation policy for independently requested incident creations; Service Bus idempotency is not implemented.
- Incident is the aggregate root. ExecutionOutcome is its immutable origin reference; IncidentDetails and IncidentLifecycle hold domain values. Execution rows remain outside aggregate ownership. Query projections do not reconstruct aggregates.
- TransitionIncidentCommandHandler locks the incident for update and the verification execution for shared read, checks capabilities, invokes domain rules, preserves historical run links and appends audit before commit. One IncidentStore port represents aggregate persistence for both creation and lifecycle commands. Concurrent operators serialize on the incident. A failure in state/link/audit persistence rolls back the complete command.
- PipelineConfigurationService edits criticality, monitoring status, environment and responsible-team metadata without changing workflow identity or creation time. CONFIGURE grants/revokes exact capabilities idempotently and audits actual grant changes. It cannot bootstrap its own initial permission.

## Approved functional policy

Explicit user approvals in this session adopt the following revisable MVP rules. They supersede earlier descriptions of these specific functional rules as unspecified; the integrated source model and wider PD-007/008/033/034/035 records retain their existing status until separately updated.

| Capability on a pipeline | Allowed behavior |
|---|---|
| VIEW | Consult that pipeline and its operational data. |
| OPERATE | Includes VIEW; start remediation, associate verification, return to remediation and resolve incidents. |
| CONFIGURE | Includes VIEW; edit that pipeline's settings and manage its access grants. Does not imply OPERATE. |
| No applicable grant | Deny. No implicit administrator or cross-pipeline permission. |

Human commands receive a trusted principal reference from their eventual identity adapter. The current internal services do not validate JWTs or make user-provided actor strings trustworthy. No human endpoint is exposed. Initial CONFIGURE grants require authorized operator provisioning outside a self-service endpoint; environment identity, principal encoding and provisioning ownership remain part of the proposed integration contract.

| Transition | Domain precondition and persisted behavior |
|---|---|
| OPEN → IN_REMEDIATION | Explicit authorized human action; record remediation start and audit actor/time. No AIOps diagnosis prerequisite. |
| IN_REMEDIATION → VERIFICATION | Associate a run started strictly after the current remediation intervention. Completion/success is not required to enter verification. |
| VERIFICATION → IN_REMEDIATION | Explicit action with a linked failed verification from this remediation cycle. Preserve previous run links and audit history; start the next intervention period. |
| VERIFICATION → RESOLVED | Explicit human confirmation with a linked verification from this cycle whose authoritative status is completed, conclusion success and failed=false. |
| RESOLVED → any state | Forbidden; no reopening in the MVP. |

The user explicitly permits verification from another authorized pipeline. The implemented interpretation requires OPERATE on the incident's pipeline and VIEW on the verification run's pipeline, because the association does not change that external pipeline. Old successful runs cannot resolve a later remediation cycle. Incident state never validates/rejects an AIOps diagnosis; no diagnosis or evidence record is overwritten. GitHub remains read-only.

Audit rows use the documented shared audit.audit_events shape and the access_and_governance_events category. Incident audit captures previous/new status, actor, correlation, time and execution reference. New configuration audit captures nullable policy/ownership metadata with snapshotVersion=2 and credential redaction; older status/criticality-only snapshots remain incomplete; grant audit captures principal/capability changes. Raw event/provider payloads, logs and credentials are excluded; only whitelisted configuration metadata is recorded. The JDBC audit adapter participates in the same local PostgreSQL transaction as JPA. Flyway V6 creates audit.audit_events if absent (Pipeline is the MVP owner per the runtime manifest); an earlier provisioned table is kept unchanged. Missing/unwritable audit storage causes the human command to roll back.

## Verification boundaries

Persistence fixtures use JDBC so ORM write/read symmetry cannot hide a broken mapping. They exercise distinct names/branches and PostgreSQL microsecond timestamps, absent IDs with populated data, nullable default branches, external-ID uniqueness and UUID primary-key integrity. Negative SQL cases check PostgreSQL SQLSTATE plus the violated constraint or column; identical repository names with distinct IDs must remain allowed.

StartupContractTests launches the actual application without the data/HTTP fixture's migration override. Against its disposable database it checks failed default startup without table creation, successful default startup after migration, the explicit Hibernate validate setting and migration through the local profile. Startup contexts omit the web server; HTTP documentation availability remains covered separately.

Incident fixtures construct equal pipeline UUIDs independently. OperationalServiceTests exercises real application services, committed PostgreSQL state, DD-002 failures, cross-pipeline verification, unsuccessful resolution, repeated remediation, retained run history, default denial, independent capabilities and concurrent operators. Test-only rejecting triggers prove rollback of incident/origin and of lifecycle/settings/grants when audit fails. These are local core guarantees; no broker delivery, JWT, public API, load or Azure behavior is certified.

Ongoing decisions and verification notes belong in this document and README, without a new document for each increment. The older bootstrap/lookup records are historical evidence. Publication of branches or PRs requires explicit user authorization.

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
| Known workflow | Resolve repository/workflow to a preregistered pipeline. Unknown workflow: abandon for redelivery (UNKNOWN_WORKFLOW, runtime manifest); RETRY_EXHAUSTED only at the delivery limit; no implicit registration. |
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

ServiceBusConfiguration runs when pipeline.messaging.enabled=true (default). It connects with the emulator connection string under `local` or the namespace with DefaultAzureCredential under `azure`, uses topic/subscription from PIPESOR_SERVICE_BUS_TOPIC/_SUBSCRIPTION, manual settlement, no prefetch and explicit concurrency/renewal/drain limits. SnapshotEnvelope validates eventType, schemaMajor, messageId == eventId and correlationId in one place; processor errors are logged with their exception and the processor state contributes to readiness. See [runtime.md](runtime.md#snapshot-receiver).

## Contrato REST v1 aprobado en sesion

Explicitly approved by the user in this session for the existing dashboard → Pipeline relationship; the routes below are implemented locally. Queries are synchronous; human commands reply after the database/audit commit. No manual incident-opening endpoint or GitHub write action is proposed.

| Exchange | Minimum permission |
|---|---|
| POST /api/v1/pipelines | Linked identity, admitted installation and verified repository read/administration; no unrelated CONFIGURE grant or registration scope substitutes for these. |
| PUT /api/v1/pipelines/{id}/policy; PUT /api/v1/pipelines/{id}/ownership | CONFIGURE plus current GitHub administration; independent section replacement. |
| GET /api/v1/pipelines/{id}/grants; GET /api/v1/pipelines/{id}/activities | CONFIGURE plus current GitHub administration; activities explicitly partial. |
| GET /api/v1/pipelines?offset=0&limit=25 | VIEW, applied in SQL before pagination; return only authorized pipelines. |
| GET /api/v1/pipelines/{id} | VIEW on id. |
| GET /api/v1/pipelines/{id}/executions; GET /api/v1/executions/{id}; GET /api/v1/executions/{id}/stages | VIEW on the owning pipeline. |
| GET /api/v1/pipelines/{id}/incidents; GET /api/v1/incidents/{id}; GET /api/v1/incidents/{id}/runs | VIEW on the incident's pipeline. |
| GET /api/v1/incidents?state=OPEN\|NEEDS_VALIDATION\|RESOLVED&pageSize&pageToken | Grants and current GitHub read applied in SQL before keyset paging; `{items, nextPageToken}` ([runtime.md](runtime.md#incident-queue)). |
| POST /api/v1/incidents/{id}/remediation | OPERATE; empty body; explicit start from OPEN. |
| POST /api/v1/incidents/{id}/verification | OPERATE plus VIEW on the candidate pipeline; body {"executionId":"uuid"}. |
| POST /api/v1/incidents/{id}/retry-remediation; POST /api/v1/incidents/{id}/resolve | OPERATE plus VIEW on the referenced execution pipeline; body {"executionId":"uuid"}; enforce linked/current-cycle rules. |
| PUT /api/v1/pipelines/{id}/settings | CONFIGURE; complete replacement of criticality, monitoringStatus, environment and responsible-team metadata; workflow identity immutable. |
| PUT or DELETE /api/v1/pipelines/{id}/grants/{principalRef}/{capability} | CONFIGURE; exact grant/revoke; repeated operation is a no-op. |

Single-resource responses mirror existing typed application projections; lists use {"items":[],"offset":0,"limit":25} without unbounded totals. List endpoints default to 25/max100, enforce nonnegative offset and use the existing stable ordering. Commands return the committed resource (200); grant operations return 204. Reject malformed input with 400, missing/invalid authentication with 401, missing capability with 403, absent resource with 404 and illegal transition/verification with 409; use application/problem+json with correlationId and no stack trace. A request correlation header is validated or replaced with an owner-generated UUID; it cannot supply identity.

> Platform runtime: bearer validation now follows the platform identity contract v1 (session tokens with `Pipeline.Access`; principalRef is the session `tid:oid`). See [runtime.md](runtime.md#routes).

Use Entra JWT bearer validation with externally configured issuer, expected tenant and API audience; validate signature/lifetime/issuer/audience and derive principalRef as tenantUUID:objectUUID from validated claims. Actor never comes from request body/header. Application scope/audience values and registration are supplied by the identity owner; no permissive fallback. Verified registration now creates the initial CONFIGURE grant atomically; recovery on an existing pipeline without an eligible CONFIGURE holder remains explicitly pending, with no implicit administrator. The user's explicit approval includes these human command routes. OpenAPI is generated from the implemented controllers/resources, including bearer security, UUIDs, paging bounds, required inputs and common problem responses; no parallel hand-written YAML is maintained. API security is stateless bearer-only, validates signature/issuer/audience/lifetime/tenant/oid/delegated scope and derives the actor from canonical UUID claims. All identity settings absent means reject every business token; partial/invalid configuration fails startup. Issuer metadata/JWKS discovery is deferred until authentication, rather than assuming local startup proves Entra availability. Documentation exposure remains profile-controlled.

Controllers do not hold SQL transactions across external checks. Human preflight obtains current GitHub facts before local write transactions; command handlers then recheck and lock the local granting rows through commit. Read requests recheck local capability after external preflight, and list SQL retains the granting predicate before pagination. Visible pipeline filtering occurs in the SQL/HQL query before pagination, without duplicate rows for multiple capabilities. Error responses include correlation references; unexpected failures omit stack traces and sensitive details. A missing capability takes precedence over existence disclosure on pipeline-scoped requests. HTTP tests use real RSA signatures and the production claim validator with a local public key, not mocked authentication. Live Entra discovery/key rotation and the deployed ACA perimeter are unverified.

## Approved GitHub access evolution

User-approved on 2026-11-06 after research; authoritative decision and service-impact matrix: [ADR-020](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-020-github-user-access-and-workflow-onboarding.md). The local implementation now enforces cumulative policy through the in-process connector port and rejects missing verification in normal runtime. Explicit connector doubles verify the local logic; no executable remote contract, GitHub connection or integrated compliance is certified. The decision is now integrated in the current consulted main; the absence of an executable remote integration remains.

### Service boundaries

- Direct impact: web_dashboard links Entra identity to GitHub and selects accessible workflows; ingestion_normalization_service hosts the technical connection component, user tokens, installation context, discovery and verified access facts; pipeline_service decides its resource authorization and owns registration/settings/grants/audit in ops.
- Security dependency: Log/Event and Recommendation must enforce the repository access restriction on their human evidence/diagnosis/review surfaces. Their context-resolution contracts are pending; this decision adds no direct calls to the connector or ops queries for them. Integrated exposure is not safe if another API bypasses revocation.
- No functional change to correlation, NLP or RAG background processing; no user tokens or GitHub integration for those services. IaC receives delivery requirements, not authorization to provision secrets, resources or permissions. No new deployable service, gateway, user/team/role tables or cross-context writes.

The approved internal relationship is rel_pipeline_ingestion_github_access: Pipeline → Ingestion via internal REST/HTTPS, providing verified actor/repository/workflow facts without GitHub credentials. The source also adds rel_dashboard_ingestion_github_connection and rel_ingestion_github_connection. Routes, schemas, caller authentication, audience/scopes and verifiable subject propagation are not specified yet. Do not trust actor/permission flags from a request body, forward tokens to an incompatible audience or call GitHub directly from Pipeline. Technical link/token storage belongs logically to Ingestion, with schema/custody still undecided; it never belongs to ops or audit.

### Approved policy and registration

Existing Entra JWT validation and delegated API scope remain required. For human access, add a current linked GitHub identity, a tenant-approved installation/account and verified repository access. VIEW and OPERATE require GitHub read access plus the existing effective AIOps capability; CONFIGURE and grant management additionally require effective repository administration. CONFIGURE still includes VIEW, not OPERATE. GitHub role alone never creates AIOps grants; grants cannot bypass GitHub access. Grant recipients must be linked and eligible; no CONFIGURE grant to a nonadministrator.

Registration requires verified repository administration and API authorization, not CONFIGURE on another pipeline or a global registration scope alone. The frontend supplies selected external IDs and AIOps policy; the connector provides verified repository/workflow metadata. Pipeline creates or reuses Repository by unique githubRepositoryId, then creates Pipeline with the previously accepted deterministic UUID rule, initial CONFIGURE for the creator and sanitized audit in one local transaction. Preserve repository/workflow uniqueness and reject duplicates with conflict, including concurrent registrations, without granting access or replacing settings. Validate the provider before starting the SQL write transaction; do not claim distributed atomicity. No preseeded repository row is required. Recovery for a new GitHub administrator without AIOps grants needs an explicit audited procedure, still pending.

Check current access per protected request without positive caching between requests in the first increment; reuse within that request. Combine allowed repositories and AIOps grants in SQL before pagination. Provider/connector timeout or rate limit fails the dependent operation as unavailable, never as an empty authorized list or fallback to stale access. Per-request checks do not eliminate the check/use race with GitHub.

User unlink/revocation blocks that person's access, including stored historical data, but does not stop shared machine monitoring while the installation remains authorized. Installation removal/suspension blocks dependent capture/access without automatic deletion. Connection state is distinct from ACTIVE/PAUSED; no defaultBranch filter or implicit backfill. Acceptance of already captured/in-flight events across revocation requires an explicit contract before changing broker settlement or execution processing. Human verification using another pipeline must satisfy authorization for both owning repositories.

### Implementation and approval limits

Preserve the earlier event contract, deterministic identity rules, settlement and incident transitions unchanged. ADR-020 itself did not approve new section/history routes; the current explicit implementation prompt authorizes policy/ownership and the later user answer authorizes grants/activity reads. Pipeline owners, token tables and infrastructure remain excluded. Complete onboarding still depends on Frontend/Ingestion owners and cannot be certified by these local tests. No neighboring repository is modified by this task.

PD-007/008 policy is approved; representation, recovery and implementation remain pending. PD-027 covers custody/rotation; PD-034 covers connector and affected human API contracts; PD-035 covers delivery and rollout; PD-033 covers any agreed revocation effect on in-flight messages. PD-036 remains the separate workflow actor contract. Source-model grant storage stays ia_only/inferred. No live GitHub/Entra or deployment validation is implied.

## ADR-020 local implementation and boundaries

Implemented locally without new tables/migrations or changes to V1–V5:

- POST /api/v1/pipelines accepts githubRepositoryId/githubWorkflowId and AIOps configuration. Canonical external IDs, required criticality/monitoringStatus and configuration metadata are validated. Actor derives from the validated Entra token; frontend names, paths, installations and permission flags cannot authorize registration.
- GitHubConnector is an in-process application port, not a proposed JSON payload or remote API. verifyWorkflow supplies verified repository/workflow metadata plus actor/subject/operation/repository/installation facts; verifyAccess supplies current subject/link and requested repository access. readable means effective user AND App access, active installation and required SSO; admittedForTenant covers the allowed account/installation context. No GitHub credential enters Pipeline.
- GitHubAccessChecks validates returned subject/actor, operation, requested repository set, installation identity and current validity. Mismatched, expired or unavailable proofs fail closed. A changed GitHub identity within one request cannot combine permissions from two links. No positive cache survives a request; non-HTTP calls get fresh sessions. The normal connector implementation always raises unavailable until an authenticated remote adapter is implemented under an accepted contract.
- HumanPipelineAccess intersects stored AIOps capabilities with current GitHub facts. VIEW and OPERATE require read, CONFIGURE requires administration. CONFIGURE still implies VIEW when read remains available, but is ineffective for administration after admin loss. Grant creation separately verifies the target subject's current eligibility for the requested capability. Revoking an obsolete grant does not require the recipient to remain connected. No automatic recovery or transfer of grants occurs.
- Registration verifies externally first. The subsequent local transaction inserts/reuses Repository by unique GitHub ID, locks the repository to serialize concurrent registrations, rejects duplicate or incompatible pipeline identity, inserts the UUIDv5 Pipeline, creates only the creator's CONFIGURE grant and appends sanitized audit. A duplicate is a typed 409, never a reconfiguration or grant. Failure of any write/audit rolls back the whole transaction. Existing UUIDs are not rewritten. New workflows may refresh verified repository display metadata; duplicate registrations do not.
- PipelineView consistently includes repositoryId, githubRepositoryId, fullName, nullable defaultBranch, workflow ID/name/file, policy/team fields, createdAt and effectiveCapabilities. List joins repository metadata and applies both verified repository eligibility and AIOps grants before offset/limit, without grant duplication. Capability lookup is batched for the page. A provider outage is not an empty result, even when no local candidate grant exists.
- Every human execution/stage/incident/run route resolves its owning pipeline/repository. Incident operations check OPERATE/read and cross-pipeline execution references additionally check VIEW/read on that repository. Incident-run reads batch-resolve every referenced execution owner and deny a page containing inaccessible references instead of exposing foreign execution IDs or filtering an already paginated page. External checks run before SQL write locks; local grants are checked again inside the existing transactional command. Domain transitions and machine event processing are unchanged.
- PUT /settings retains full replacement and its existing omitted-optional-field clearing behavior. PUT /policy requires criticality, monitoringStatus and the nullable environment key; PUT /ownership requires both nullable team keys. Explicit null clears a field, omission is 400 for these new section routes. A row lock and section-specific assignments preserve the other section under concurrent writes. Workflow identity and createdAt stay immutable; team fields do not create pipeline owners.
- Current-user effectiveCapabilities is the only capability identity data in pipeline representations. GET /grants and GET /activities were separately approved by the user in this session and require CONFIGURE plus current GitHub administration. Grant reads return id/principalRef/capability/createdAt, never inferred user profiles or GitHub roles. Both use offset=0, limit=25/max100, stable order; activity reads sort occurredAt/id descending and whitelist this service's pipeline governance actions/category.
- Activity responses explicitly carry historyComplete=false. Version-1 missing environment/ownership values stay absent; no values are reconstructed. Version-2 snapshots include nullable configuration fields. History reads whitelist metadata and redact recognizable credentials instead of copying arbitrary JSON. No diagnosis/evidence/other-owner audit is joined or exposed.

OpenAPI is generated from these controllers/resources: POST returns 201 and Location, duplicates return 409, missing linkage is 403 with GITHUB_CONNECTION_REQUIRED, denied capability/provider access is 403 and unavailable/expired/misbound verification is 503. Integer paging types/bounds/defaults and nullable section semantics are declared and verified against generated output. No independent hand-written OpenAPI schema is maintained.

Local verification uses PostgreSQL and real HTTP/JWT signature validation with an explicit GitHubConnectorDouble in test sources only. Its unconfigured subjects/resources are denied and it asserts no connector invocation occurs inside a SQL transaction. Tests cover Register → List → Get → policy → ownership, creation/reuse, duplicate/concurrent registration, malformed/legacy identities, proof binding, missing linkage, tenant admission, read/admin loss, recipient eligibility, provider failure, filtering before pagination, concurrent independent updates, settings compatibility, audit rollback/redaction, partial history, request-only proof reuse and child/cross-resource authorization. ConnectorUnavailableTests uses the production Ingestion connector with Ingestion unreachable, not that double. Executed on 2026-11-06: ./mvnw -B -ntp clean verify passed with real PostgreSQL and all tests enabled. Standalone javac compiled the domain/application sources with an empty dependency classpath; jdeps reported java.base only. Local documentation links/anchors, whitespace and git diff --check passed. The packaged JAR excludes the connector test double. These checks do not certify live GitHub/Ingestion, Entra discovery or deployment.

Blocked external work remains precise: Ingestion must provide a versioned executable access/metadata contract, authenticated service calls, verifiable subject propagation, bounded timeout/rate-limit/error mapping, linked identity/installation/SSO semantics and secure credential custody. No routes, payloads, audiences, credentials or propagation mechanisms are selected for that API here. Frontend owns connection/discovery UI and sends selected IDs; it cannot authorize from flags. Log/Event and Recommendation owners need an agreed authorization context so evidence/diagnostic/review APIs cannot bypass repository restrictions; this task adds no calls/ops reads for them. IaC/identity owners retain separately authorized configuration and rollout. Normal Pipeline runtime cannot provide an operational GitHub onboarding flow until those dependencies are resolved.

The GitHub check/use race remains: this is a local SQL transaction after verification, not a distributed transaction or instantaneous revocation guarantee. Connection loss does not delete historical data, change retention, modify ACTIVE/PAUSED or stop shared machine monitoring. Registration/reactivation does not backfill. defaultBranch is informational, event processing has no branch filter and criticality does not derive incident severity. Incident responsibility remains distinct from pipeline team metadata.

## Machine operational-reference validation — approved local increment

The user approved the Log/Event → Pipeline validation proposal in Correlation's
PD-031 addendum. `ValidateOperationalReferences` provides an internal operation
for the automatic ORIGIN case. Every call first requires its explicitly injected
machine authority to confirm the exact tenant/account/installation/repository/
workflow/revision binding for the authenticated caller. Machine authority remains
a required composition dependency. Its response precedes any owned-row lookup,
including deterministic-ID mismatch reporting. The existing human access flow
continues to use its own authority.

The operation preserves the correlation ID and validates deterministic pipeline,
execution/attempt and incident identity. One PostgreSQL statement verifies the
committed repository/workflow, execution run/attempt and failed ORIGIN membership.
Absent owner rows return `NOT_READY`; committed contradictions return `MISMATCH`.
Denied or unavailable authority prevents the lookup, and failed reads return
`UNAVAILABLE`. Responses contain only the decision. A new processing attempt
always revalidates authorization. The scope revision establishes current
permission when the authority checks it; historical message attestation requires
the separate owner contract.

The operation is manually composed for local verification. Authentication,
request/response serialization, trusted deadlines and the remote
Pipeline → Ingestion scope adapter must be implemented under the approved
cross-service contract before enabling the Log/Event integration. Caller tokens,
audiences, numeric budgets and broker settlement remain environment/contract
inputs. PostgreSQL fixtures exercise the existing snapshot handler to create
owner records; authority responses in these fixtures are synthetic.
