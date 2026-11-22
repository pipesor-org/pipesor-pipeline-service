# Deployment contract: pipeline_service

**Status: readiness record; not approved for IaC consumption or deployment.** Prepared for PD-035. [Architecture consumption](architecture.md) records the consulted source revision `c84ef3e4629fcb0c38315f32b641a5fb949e450d`, model IDs, applicable ADRs and committed PD-033–036. ADR-019 governs the current MVP infrastructure alignment. [ADR-020](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-020-github-user-access-and-workflow-onboarding.md) adds the approved GitHub user-access/onboarding evolution; it is integrated in current pipesor-docs main `e22627cfee10b0b02edab084b98eb18926c678b5`, with [service-specific impact](architecture.md#approved-github-access-evolution).

**Documented fact** means recorded in those sources/policies, not proven implementation or independent approval evidence. **Proposal** requires acceptance; **Unknown** remains pending. The local implementation includes startup, opt-in OpenAPI/Swagger UI, internal operational queries, incident lifecycle and pipeline configuration commands, per-pipeline grants and atomic functional audit. Flyway V1–V5 manage ops; the shared audit schema is a separately provisioned dependency. Business endpoints and an opt-in receiver are implemented locally; a container image, CI/build/publish, guarded recovery and disabled release workflows are now implemented locally; no image has been published and no environment credentials are supplied. No live Entra/Service Bus/ACA integration is certified. IaC contains infrastructure preparation; provisioning is not evidenced here.

## Identity and runtime

| Requirement | Value | Source and status |
|---|---|---|
| Identity and responsibility | `pipeline_service` — Own monitored workflows, executions, stages, states, metadata and incidents in the ops context. | Documented fact: `elements[pipeline_service]`, ADR-018. |
| Independent deployment | Yes; separate backend repository/container on Microsoft Azure Container Apps. | Documented fact: `deployment.container_hosts.pipeline_service`, ADR-001/015/018 as applicable. No deployed service resource is evidenced by this repository. |
| Language/framework | Java / Spring Boot. | Documented fact: element `backend`, ADR-018. See the session-authorized development baseline below. Dependency pins and base-image selection remain implementation/delivery requirements under PD-032/035. |

## Development baseline

User direction recorded on 2026-11-06 retains the installed Java 25.0.4.1, Python 3.14.8, Docker 29.9.3 and Git 2.55.0. Java services use Maven (installed: 3.10.0); Azure CLI 2.92.0 is available for separately authorized operations. Select the latest stable dependency versions compatible with that baseline, and pin them when application build/dependency files are introduced. Framework and transitive dependency compatibility must be checked together; availability on this workstation is not container/CI verification. Python dependencies belong in isolated project environments.

This session direction supplements the consulted architecture revision; it does not close PD-032's identity/hash choices or PD-035's base-image and delivery requirements. The [first controlled increment](implementation-bootstrap.md) introduces a Maven project targeting Java 25, Spring Boot 4.1.2, springdoc 3.2.0 and Maven Wrapper 3.10.0. Its local build and HTTP startup checks passed. The current local delivery checks additionally cover the packaged container; hosted Actions, GHCR publication and Azure compatibility remain unverified.

## Integrated infrastructure baseline

The [IaC resource baseline](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/mvp-resource-baseline.md) at `pipesor-iac@4b42c0c8e66578dfb890ae1cca8c9c31c774026e` selects GHCR and system-assigned identity per future Container App. Identity is created with its app; it is not an existing identity or a scoped access grant. The [native-ingress decision](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-003-mvp-native-ingress.md) preserves service-side authorization, and [ADR 004](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-004-mvp-state-and-deployment.md) restricts current preparation to dev with separately authorized activation and Azure actions.

Shared opt-in Terraform foundations include the external-capable ACA environment/VNet, Entra-only public Service Bus Standard namespace, private Entra-only PostgreSQL 17 Flexible Server (B2s, 32 GiB, seven-day local backups, no HA), shared Log Analytics/Application Insights and public Entra-only Foundry account/project. These are approved IaC preparation choices with checked-in infrastructure code, not evidence of deployed or reachable dependencies. They do not add a database, broker or AI relationship to a service that lacks one in the architecture model.

PostgreSQL's `vector` allowlist setting does not execute SQL extension creation, schemas, migrations or grants. Broker entities/delivery semantics, SQL/token access, operator/migration connectivity, instrumentation, model/API compatibility, image pulls and required per-service contracts remain pending. Platform/database backup and telemetry retention do not implement the 365-day evidence policy. Read the [documentation baseline](documentation-baseline.md) for source and evidence limits.

## Artifact and responsibility boundary

| Requirement | Value | Source and status |
|---|---|---|
| Delivery unit | Container image for this service. | Documented fact: `deployment.containerization`, ADR-001/018. |
| Immutable identity | Associate the image with its source commit and digest; promote the same digest through the staged delivery flow. | Documented organization target: CONTRIBUTING artifact promotion. Local CI builds/tests one linux/amd64 image; the separate publish job transfers that same image and records its GHCR digest. Hosted execution/publication remain unverified. |
| Release/version | Policy targets Conventional-Commit-derived SemVer and protected `vX.Y.Z` tags after staging, without rebuilding. | Documented organization target; local release tooling is prepared and source-disabled, with no hosted publication verified. GHCR is selected in the integrated IaC baseline. The local implementation publishes technical candidates under ghcr.io/<repository>, with source/run/attempt tags and digest handoff; integration assigns no version. A separate disabled release workflow names selected candidates as SemVer RC/stable without rebuilding; stable retains the future preprod evidence gate. Private-pull credentials remain pending under PD-035. |
| Ownership handoff | Proposed: this service's maintainers supply the tested image/digest and runtime/configuration requirements; IaC maintainers consume the approved delivery inputs for the agreed environment. | Service-owned CI publication and candidate handoff are implemented locally. Cloud resource ownership and the runtime/configuration handoff remain pending under PD-035. |
| Promotion responsibility | Independent QA approval before staging and independent product-owner approval before production are policy targets. | Documented organization target. Concrete automation, environment mapping and operational owners remain unknown under PD-035; do not infer them from the sibling IaC implementation. |

Current target: only `dev` is selected for temporary academic MVP preparation (ADR-019 and IaC ADR 004). Local service integration/validation, complete readiness/access/cost review and explicit target/action authorization precede Azure writes. The dev procedural approval exception does not extend to future environments; retain independent QA/production promotion rules when commissioned.

## Shared CI/CD reference

The [shared CI/CD guide](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md)
owns the reusable principles, versioning convention, candidate-validation producer
contract and future dev → qa → preprod → prod sequence. It distinguishes the
implemented pilot from proposed adoption in Java, Python, SPA and IaC. This service
keeps its runtime/readiness contract here and its runnable procedures in README;
no cross-environment promotion or upstream ADR amendment is implemented.

## Execution

### Local delivery implementation

This increment prepares CI/container/publish tooling and inactive release/deployment workflows for integration; it does not authorize Azure activation. [README delivery operations](../README.md#ci-images-and-disabled-delivery) owns commands, candidate schema, retry/retention behavior, authorization checks and activation prerequisites. The [rollback implementation/runbook](../README.md#rollback-implementation-and-runbook) owns recovery triggers, stop conditions, exact candidate selection, dispatch and acceptance; the explicit rollback operation verifies an earlier same-target successful delivery and original candidate provenance before reusing the image-only adapter. It records operation, producer identity and rollback origin; operational recovery remains unverified in Azure. No upstream ADR is amended. V1–V5 and D03-EXP are suspended; D03 remains unapproved.

Implemented locally: PR/main verification, nonroot linux/amd64 container, tested-image transfer into an isolated GHCR publish job and candidate digest metadata. CI publication is configured but has never run remotely. The native ACA adapter is hard-disabled, accepts only verified main candidates from a CI artifact or retained Release manifest and an exact existing app, and changes only its image. Recovery evidence is persisted before mutation and collected on failures as well as success. `operation=rollback` binds a retained candidate to an earlier successful deploy.yml attempt and versioned ready receipt for the exact target, rejects invalid evidence before Azure and records the selected rollback origin. Functional acceptance/schema compatibility remain operator assertions; no automatic failure-triggered rollback or SQL recovery is introduced. The release workflow is separately source-disabled, uses commitlint/semver libraries and preserves the stable preprod gate without provisioning that environment. GHCR/Release deletion remains disabled; current and recovery artifacts/provenance must be retained. Its Azure responses are simulated in unit tests; no OIDC, RBAC, registry pull or Azure deployment has been tested.

Proposed activation contract: the service pipeline owns release artifacts; infrastructure and application lifecycles remain separate. Native image updates require explicit acceptance of image/configuration ownership, no competing Terraform reconciliation of that field and cross-pipeline write coordination. This adapter neither makes `ignore_changes` safe by itself nor defines a shared-ownership standard. If Terraform remains the application owner, replace the inactive adapter before activation. IaC ADR 004 readiness and separate resource/action authorization remain mandatory. No exception or cloud resource is authorized by implementing this local increment.

Still blocking Azure activation: accepted per-service runtime and configuration contracts, reachable Entra-only PostgreSQL/schema/audit provisioning and migration execution, suitable probes/startup behavior, identity/grants, private GHCR pulls, existing single-container/single-revision target and environment, accepted ownership/coordination, and explicit activation authorization. The container binds `0.0.0.0:8080` for local image verification only; cloud ingress/port approval is pending. ACA control-plane readiness will not prove business functionality or full service integration. Local synthetic datasource credentials and the migration-enabled local profile must never become Azure defaults.

Documented fact: Documented Service Bus subscription delivery and REST/HTTPS queries from the dashboard through ACA native ingress (rel_dashboard_pipeline_service). Exact exposure and caller authorization remain pending. Sources: service relationships listed in [architecture consumption](architecture.md), ADR-015/016/019 where applicable.

| Pending runtime value | Status and approval required |
|---|---|
| Listening port and bind address | Unknown, PD-035; framework defaults are not approved contract values. |
| Ingress scope, routing, TLS termination and caller authorization | Unknown beyond the documented caller/protocol above; PD-034/035, and PD-036 for workflow access where used. Do not add public exposure. |
| Liveness/readiness/startup probes | Paths, protocol, port, criteria and timings unknown, PD-035. No health endpoint is selected or implemented. |
| Startup | Initialization order, dependency retries, startup budget and migration coordination unknown, PD-035. |
| Shutdown | Graceful termination/drain behavior, time budget and in-flight work handling unknown, PD-035; broker consumers also depend on PD-033 re-delivery agreement. |

Local bootstrap settings bind HTTP to `127.0.0.1`; OpenAPI/Swagger UI are disabled by default and enabled by the `local` profile. Verification used a random port (`server.port=0`). These local settings do not resolve the pending ACA port/bind/probe/authorization contract above. Database targets and credentials must now be supplied externally through Spring datasource configuration. PostgreSQL-backed tests use disposable containers; the local profile enables Flyway, while the default disables migration execution and requires an existing schema for Hibernate validation. Broker reception is opt-in with all namespace/subscription/delivery inputs required; no target is configured or contacted in local checks. The README lists the actual nonsecret API identity and receiver configuration names. Their target values, Entra registrations/grants and broker/ACA setup remain delivery inputs under PD-027/035.

## Configuration and secrets

| Category | Known requirement and supplier | Status/source |
|---|---|---|
| Nonsecret integration settings | Service Bus subscription target, PostgreSQL ops connection target and monitored-workflow configuration. Names and target values remain pending PD-008/033/035; ADR-020 approves the registration policy, not its integration/configuration. Actual environment values and their suppliers are not assigned. | Targets/capabilities are documented in the model; exact settings and supplier allocation are unknown. |
| Secret/authentication requirements | Service Bus and PostgreSQL use the integrated Entra-only foundation with system-assigned identity for the future app. Token acquisition/refresh, scoped grants, suppliers and service caller validation remain pending PD-007/027/034/035. | Unknown except explicitly documented mechanisms; PD-027/035. Secret storage is intentionally undecided. |
| Delivery of values | Proposed: backend maintainers define/validate the required configuration interface; the authorized environment/integration owners supply approved values through the agreed IaC handoff. | Proposal, PD-035; specific people/teams, rotation and distribution mechanism remain unknown. |

No Azure application environment-variable contract, credentials, secret store or runtime defaults are approved by this document; the local datasource inputs are scoped to the local persistence increment below. The system-assigned ACA identity lifecycle is selected in IaC; its creation, access grants and user/workflow authentication remain separate requirements.

## Access and integration

| Downstream | Intended operation and boundary | Canonical source |
|---|---|---|
| `ingestion_normalization_service` | Approved internal REST/HTTPS access/metadata verification, without GitHub tokens. Versioned contract, service authentication, subject propagation, timeouts and target values pending. | ADR-020; rel_pipeline_ingestion_github_access |
| `postgresql_operational_store` | Data plane: read/write owned tables only; no database control-plane authority. | `rel_pipeline_service_postgresql`; PostgreSQL |

Documented fact: incoming consumers/callers are listed in [service relationships](architecture.md#service-relationships). Protocols and directional operations above describe data access, not granted permissions. Control-plane access for Azure/GitHub provisioning or administration is not part of the service runtime contract; no grants are authorized. Exact credentials, audiences, scopes, network rules and permission sets remain unknown (PD-027/034/035 and PD-036 if used). Embedding-provider descriptions do not authorize undeclared direct calls.

## Data lifecycle

Documented ownership from ADR-018 and `model/domain.yml.entities`:

`ops.repositories` (confirmed), `ops.pipelines` (confirmed), `ops.pipeline_executions` (confirmed), `ops.execution_stages` (confirmed), `ops.incidents` (confirmed), `ops.incident_runs` (confirmed), `ops.pipeline_access_grants` (inferred)

One physical PostgreSQL instance is shared; queries and commands use logical CQRS. Writes are confined to owned tables; shared schemas do not grant cross-owner writes. Physical FKs may exist within a schema; cross-schema references are logical IDs, with no ORM navigation across contexts (ADR-018, DD-014, PD-031). `audit.audit_events` has shared cross-cutting ownership and is not assigned exclusively to this repository; exact shared audit-write mechanics remain pending PD-035. Inferred `pipeline_access_grants`, where listed, preserve `ia_only`/inferred status. ADR-020 approves the MVP policy; PD-007/008 still track representation, recovery and implementation contracts. No persistent schema is added for technical processing services without documented ownership.

**Proposal (PD-035):** the owner of each table authors and validates its migrations; IaC consumes approved compatibility and execution requirements without owning functional schema changes. Migration author/runner approval, sequencing, execution mechanism, rollback/recovery and deployment ordering are **unknown**; Flyway is selected for Java services with owned tables by the session-authorized [Java/Spring conventions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/java-spring-conventions.md); no migration runner or job is selected. Services without owned tables do not acquire migrations through that choice. Services without owned tables have no owned-schema migration payload; do not infer new tables to fill the gap.

**Documented facts:** logical references must be validated through existing contracts (PD-031). Logs, diagnoses and evidence have the documented 365-day retention policy (ADR-013/016/018, INV-009, domain RET-001); do not extend that duration to corpus, configuration or audit. Policy evidence and purge mechanisms remain PD-017/030.

**Unknown (PD-033/034/035):** accepted message/API/schema compatibility rules, supported producer/consumer version skew, migration rollout sequencing and validation evidence. No proposed DTO, class design or embedding dimension is treated as an approved contract. For knowledge storage, vector dimension and corpus updates remain PD-005/006; no migration can assume either.

## Local persistence implementation

> **Platform runtime update:** Flyway now runs at every startup and `V6` creates
> `audit.audit_events` if absent (Pipeline is the MVP owner per the runtime manifest). Human API
> authentication uses platform session tokens, the GitHub connector calls Ingestion, and PostgreSQL
> uses password auth under `local` and Entra tokens under `azure`. Current inputs are in
> [runtime.md](runtime.md); statements below about disabled Flyway, the shared-audit fixture, Entra
> JWT settings and the unavailable connector are historical.

Flyway V1–V5 create the six confirmed owned ops tables, DD-002 origin protection, technical execution_event_receipts for the approved delivery policy and the locally adopted pipeline_access_grants representation for the session-approved policy. The [current implementation and approvals](architecture.md#implemented-operational-core) distinguish source-model status from these user decisions. History remains ops.pipeline_flyway_schema_history. Hibernate validates mappings, never evolves schema; UUIDs are owner assigned and times preserve microseconds. No updated_at is inferred. The explicitly authorized registration/section/administration routes use these existing tables; this task adds no migrations or credential/link storage.

Human commands require writable shared audit.audit_events in the same PostgreSQL transaction. Service migrations do not claim ownership of that shared schema. Local/test setup uses a disposable canonical-shape fixture; production shared-schema provisioning, runner, compatibility and grants remain PD-035. A missing/unwritable audit table fails the operation and rolls back business writes. JWT/principal mapping is implemented in the REST adapter using configured issuer/tenant/audience/delegated scope; missing complete configuration denies tokens, partial configuration fails startup. Signature/claim behavior is verified locally, not against live Entra metadata/JWKS.

Local/test Flyway execution may initialize ops in its disposable database. These local choices do not approve creation of a cloud schema, grants, an Azure migration runner or runtime DDL permissions. Default startup leaves Flyway disabled. The README's standard SPRING_DATASOURCE_URL/USERNAME/PASSWORD inputs are local setup examples; production PostgreSQL remains subject to the documented Entra-only foundation and pending token/access/configuration contracts. Do not reuse synthetic test credentials or the local profile as an Azure deployment contract.

## Operations

Documented facts: Azure Monitor and Application Insights for application telemetry (ADR-002); correlationId, traceability and cross-cutting functional audit are model requirements (`audit_trail`, CTRL-003/004). Functional audit in PostgreSQL is distinct from technical telemetry. Evidence protection before NLP/RAG/LLM, read-only GitHub access and human control remain applicable invariants.

Unknown under PD-035: application instrumentation/token handling, log format/export, sampling, alerts, dashboards, operational ownership, SLOs, replicas, autoscaling triggers, concurrency, application CPU/memory/storage, actual region/names and availability budgets. The integrated foundation choices above do not settle those per-service contracts. Model evaluation targets are not operational SLOs. PD-029 also preserves unresolved operational degradation/version-update mechanisms for AI-related capabilities. No per-service resources or executable deployment configuration are inferred.

## Approval and unresolved decisions

Readiness blockers for the affected increment: [PD-035](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-032](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-003](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-007](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-008](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-015](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-031](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-033](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-034](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). PD-033/034/036 apply only to the exchanges this service uses. These link to existing pending IDs; this record neither creates nor closes decisions.

Before IaC consumes this contract, provide:

1. A versioned contract revision with actual image source commit/digest, runtime versions, required execution/configuration/access inputs and explicit backend/IaC responsibilities for the deployment increment.
2. Traceable decisions/evidence for each required unknown or accepted proposal, including relevant event/REST/workflow contracts, credentials/secret handling and data/migration compatibility. Resolve or explicitly accept the source-provenance limitations affecting that increment; absent fields are not defaults.
3. Identifiable approval by the authority recorded in the committed pending-decision register: CODEOWNERS **Jhon Galvez and Ramiro Guzman** for backend–IaC coordination and applicable contracts. The rule for one versus both approvals and GitHub handles/enforcement is unknown; do not invent CODEOWNERS configuration. Reference the committed source revision and provide identifiable approval evidence for this delivery contract; source availability alone does not approve it.
4. The applicable independent environment promotion approvals and separate authorization for any infrastructure action. Contract approval alone does not authorize deployment.

**Approval evidence today: none for this service's delivery contract.** ADRs retain their recorded Accepted status; missing individual acceptance records remain PD-014. This document is sufficient to expose gaps while repository initialization proceeds; it is insufficient to claim deployability.

## GitHub access delivery dependency — ADR-020

The approved connector lives in Ingestion/Normalization; Pipeline receives verified access/metadata through an internal API. Pipeline owns no GitHub client secret, private key, user/refresh/installation token, callback or discovery endpoint. Link/credential storage has logical Ingestion ownership, but schema, secure custody, keys, migrations and runtime permissions remain pending; do not assign ops/audit storage or provision a secret store implicitly.

Before activating ADR-020-dependent human endpoints, agree connector address/version, service caller authentication and verifiable user propagation, tenant-approved installations/accounts, timeout/rate-limit behavior and rollout. Missing access verification must deny/defer the dependent operation, not fall back to existing grants. Local code now enforces the cumulative conditions through a minimal in-process connector port and fails closed with 503 when verification is unavailable. No executable remote contract/adapter exists: tests use explicit connector doubles, and integrated ADR-020 compliance is not claimed. Log/Event and Recommendation human surfaces need an authorization contract to prevent exposure of the same protected data through another service; no new service relationships are inferred here. Background correlation/Python flows retain machine contracts.

Frontend and Ingestion owners still deliver linking/discovery/callback/revocation and authenticated verified facts. Pipeline locally implements registration, cumulative authorization, filtering before pagination, independent section updates, administration reads and atomic grants/audit; its remote adapter and end-to-end integration remain blocked. IaC supplies only separately approved runtime/network/identity/custody configuration. No resources, permissions, credentials, image or deployment are authorized by this record. Global registration scopes or manual declarations are not substitutes for verified repository administration. Connection loss does not change monitoring_status or authorize deletion/discard of in-flight events; that boundary still needs its delivery contract.

The implementation agreement authorizes POST /api/v1/pipelines and PUT /policy and /ownership, preserving PUT /settings; a subsequent explicit session approval authorizes GET /grants and /activities. This does not authorize new connector endpoints, token propagation, resources or rollout. ADR-020/model evolution is integrated in the consulted pipesor-docs main e22627cfee10b0b02edab084b98eb18926c678b5. CI/CD consolidation prepares this service and the shared guide for reviewed integration; it does not change that connector boundary or authorize Azure activation.

No GitHub runtime URL, audience, authentication secret, token environment variable or permissive local switch is introduced. A concrete owner-approved remote contract is required before replacing the unavailable connector. Existing Entra API and PostgreSQL configuration remain necessary and independent. Audited registration now supplies the initial CONFIGURE grant after verified repository administration, without preloaded Repository rows or a default administrator. Existing-pipeline grant recovery remains unimplemented pending its explicit procedure.

## Internal evidence owner validation v1

> **Superseded configuration (platform runtime):** the route is now always served by a Spring MVC
> controller behind the platform filter chain. Callers are the route catalog's `correlation`
> (`Correlation.OperationalReferences.Check`) and `log-event` (`Evidence.Check`) workloads; the
> Ingestion check uses a Pipeline workload token (`Pipeline.MachineScope.Check`) and
> `PIPESOR_ENDPOINTS_INGESTION`. The Entra issuer/client/principal settings and
> `pipeline.owner-validation.enabled` below no longer exist; limits keep their meaning. See
> [runtime.md](runtime.md). The request/response contract below is unchanged.

`/internal/v1/evidence/operational-reference-checks` is disabled unless
`pipeline.owner-validation.enabled=true`. Explicit configuration supplies
the Entra v2 issuer, signing-key URI, directory tenant, recipient audience,
permitted Log/Event client and principal, operation role, clock skew,
request time/size and concurrency limits, Ingestion HTTPS endpoint and
token scope, connection timeout and response size limit. The Ingestion
check uses a recipient-specific managed identity token and the remaining
deadline. The owned PostgreSQL read occurs after Ingestion authorizes the
capture scope. Deployment still requires the actual identities and inputs.

The optional Correlation caller permission uses
`pipeline.owner-validation.correlation-client-id`,
`correlation-principal-id` and `correlation-role`. Supply all three together.
Each must be distinct from the corresponding Log/Event setting. When absent,
the endpoint accepts only its existing Log/Event caller. Authorization
matches one entire client/principal/role tuple. It then applies the same
current-scope and committed-reference checks. Mixing credentials from the two
callers is denied before any Ingestion request.
