# pipesor-pipeline-service

Pipeline Service owns monitored workflows, executions, stages, states, metadata and incidents in the `ops` context. Its canonical architecture ID is `pipeline_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The application targets Java 25 / Spring Boot 4.1.2 with Maven Wrapper 3.10.0 and local OpenAPI/Swagger UI (springdoc 3.2.0). Local code implements monitored-workflow registration, inventory/detail, independent policy/ownership updates, per-pipeline effective capabilities, grants/activity queries, operational reads, incident lifecycle and transactional audit. Human operations require both AIOps grants and current verified GitHub access under [ADR-020](docs/architecture.md#approved-github-access-evolution). Domain/application remain framework-free; JPA and SQL adapters implement persistence. The existing opt-in Service Bus consumer, deterministic IDs, delivery semantics and incident transitions are unchanged by this task.

**Connector integration is blocked:** the approved ADR-020 source is a local pipesor-docs overlay, not integrated main, and no executable connector contract is available. Pipeline defines only the minimal in-process GitHubConnector port. Normal execution uses an unavailable adapter: dependent human operations fail with 503 instead of falling back to grants. There is no permissive local profile. Automated tests supply explicit test-only facts and real PostgreSQL/HTTP; they are not live GitHub/Ingestion integration. No GitHub tokens, OAuth, discovery, webhooks or GitHub HTTP client belong to Pipeline. See [current local implementation and boundaries](docs/architecture.md#adr-020-local-implementation-and-boundaries). Nothing in this working branch has been published.

## Documentation

- [Repository lookup increment](docs/implementation-catalog-query.md): implemented layers, migration scope, integration tests and remaining contracts.
- [Bootstrap implementation record](docs/implementation-bootstrap.md): scope, dependency selection, acceptance criteria and actual verification results.
- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [First implementation agreement](docs/architecture.md#first-implementation-agreement): package structure, handlers, persistence, identity, audit metadata and OpenAPI conventions.
- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the toolchain policy. Run commands from this repository root with JDK 25. Tests now require a working Docker daemon: Spring Boot/Testcontainers starts disposable PostgreSQL 17.11 instances, applies Flyway and validates JPA. First execution needs HTTPS access to Maven Central and the image registry plus writable caches. No Azure access or existing database credentials are required for tests. Missing Docker fails the integration suite.

Compile, run the domain and integration tests and package the executable JAR:

```bash
./mvnw -B -ntp clean verify
```

For an interactive local run, start a disposable PostgreSQL database (these synthetic credentials are exclusively for this local example):

```bash
docker run --rm --name pipeline-postgres-dev \
  --publish 127.0.0.1:5432:5432 \
  --env POSTGRES_DB=pipeline_dev \
  --env POSTGRES_USER=pipeline_dev \
  --env POSTGRES_PASSWORD=pipeline_dev \
  postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3
```

Wait for PostgreSQL to report that it accepts connections. Initialize the shared audit schema once in this disposable database using the canonical-shape test fixture (this is separate from service Flyway ownership):

```bash
docker exec -i pipeline-postgres-dev psql -U pipeline_dev -d pipeline_dev < src/test/resources/db/shared-audit.sql
```

Then supply that database connection and start the packaged application:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pipeline_dev \
SPRING_DATASOURCE_USERNAME=pipeline_dev \
SPRING_DATASOURCE_PASSWORD=pipeline_dev \
java -jar target/pipesor-pipeline-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=0
```

The local profile applies migrations to this development database and enables API documentation. Use it only with a disposable development target. Without the local profile, Flyway and documentation are disabled; Hibernate still validates the schema, so startup requires an existing compatible database. No credentials or database target default is embedded in application configuration.

Read the assigned HTTP port in the `Tomcat started on port` log entry. OpenAPI: `http://127.0.0.1:<port>/v3/api-docs`; Swagger UI: `http://127.0.0.1:<port>/swagger-ui/index.html`. The specification documents the implemented business paths, bearer authentication, typed resources and errors. HTTP binds to `127.0.0.1`; these settings are local development choices, not an approved ACA runtime/access contract. Internal query and command services are composed by CatalogConfiguration, OperationsConfiguration and GitHubAccessConfiguration. The REST adapter obtains actor references exclusively from validated Entra claims; internal application entry points remain trusted calls. There is no public actor header/body or bootstrap permission endpoint; registration grants CONFIGURE to its verified creator in the same transaction.

To configure human API authentication, supply PIPELINE_SECURITY_ISSUER, PIPELINE_SECURITY_TENANT, PIPELINE_SECURITY_AUDIENCE and PIPELINE_SECURITY_SCOPE together. Issuer must use HTTPS; tenant is the expected UUID; scope is the required delegated permission in scp. These are environment-specific nonsecret identifiers, not values supplied by a request. With all four absent every business token is rejected; partial configuration fails startup. Functional per-pipeline grants are still required after successful authentication. Grants use canonical tenantUUID:objectUUID references. POST /api/v1/pipelines accepts selected external IDs and AIOps configuration, verifies repository/workflow metadata and effective administration via the connector, then creates/reuses Repository and creates Pipeline, initial CONFIGURE and audit atomically. It does not require a preloaded repository. Duplicate registration returns 409 without changing settings or granting access. With the remote adapter pending, that registration path is executable in the automated local tests, not as a working GitHub connection in normal runtime. Existing pipelines without a remaining eligible CONFIGURE holder have no automatic grant recovery.

Service Bus is off by default. Activating it requires PIPELINE_MESSAGING_ENABLED=true plus PIPELINE_MESSAGING_NAMESPACE, PIPELINE_MESSAGING_TOPIC, PIPELINE_MESSAGING_SUBSCRIPTION, PIPELINE_MESSAGING_MAX_MESSAGE_BYTES, PIPELINE_MESSAGING_CONCURRENCY, PIPELINE_MESSAGING_LOCK_RENEWAL_SECONDS and PIPELINE_MESSAGING_DRAIN_SECONDS. The namespace is its fully qualified host. Delivery limits must be positive (renewal may be zero); the subscription must support peek-lock delivery/maxDeliveryCount=10 and the application identity must have scoped receive/settlement rights. The adapter uses system-assigned managed identity and creates no broker entities. Do not enable it against a real target without its integration authorization and readiness agreement. Local verification never requires these settings or Azure credentials.

The frontend-facing pipeline responses consistently include repositoryId, githubRepositoryId, fullName, defaultBranch, workflow identity/name/file, operational settings, team references, createdAt and the current user's effectiveCapabilities. VIEW requires GitHub read; OPERATE requires read; CONFIGURE requires administration. CONFIGURE and OPERATE each imply VIEW but remain independent. Registration creates CONFIGURE, not OPERATE. Stored grants never override lost GitHub access, including historical child resources.

| Endpoint | Behavior and required authorization |
|---|---|
| POST /api/v1/pipelines | Verified linked identity, tenant-admitted installation, effective read and repository administration; 201 with Location. Metadata comes from the connector, not frontend names/paths. |
| GET /api/v1/pipelines; GET /api/v1/pipelines/{id} | AIOps VIEW plus current GitHub read; repository eligibility and grants are combined before pagination. |
| PUT /api/v1/pipelines/{id}/settings | Existing complete replacement retained: mandatory criticality/monitoringStatus; omitted or null optional fields clear their values. |
| PUT /api/v1/pipelines/{id}/policy | Replace criticality, monitoringStatus and environment. All three keys must be present; environment:null clears it. Preserve ownership, including concurrent updates. |
| PUT /api/v1/pipelines/{id}/ownership | Replace responsibleTeamRef and responsibleTeamNameSnapshot. Both keys must be present; explicit null clears that value. Preserve policy, including concurrent updates. No pipeline owners are introduced. |
| PUT /api/v1/pipelines/{id}/grants/{principalRef}/{capability}; DELETE counterpart | CONFIGURE plus current administration. Granting additionally checks the recipient's link/access, and administration for CONFIGURE. Revocation does not require the recipient to remain connected. |
| GET /api/v1/pipelines/{id}/grants | CONFIGURE plus current administration. Lists stored grant identities/capabilities, not GitHub roles or user profiles. |
| GET /api/v1/pipelines/{id}/activities | CONFIGURE plus current administration. Sanitized pipeline-only audit; historyComplete=false. Old missing fields remain absent. |

Every settings/section update also requires CONFIGURE plus current GitHub administration. Executions, stages, incidents, incident runs and human incident commands resolve their owning repository and apply the same cumulative checks; cross-pipeline verification checks both resources. Missing GitHub linkage returns 403 with GITHUB_CONNECTION_REQUIRED, denied access returns 403 and unavailable/expired/misbound verification returns 503. The normal unavailable adapter makes this boundary observable after JWT validation. Connector results may be reused only inside one HTTP request, never positively cached between requests.

Lists retain offset=0 and limit=25 defaults; offset is a nonnegative integer and limit is an integer from 1 to 100. Policy/ownership audit snapshots now include all nullable configuration fields (snapshotVersion=2); earlier records are incomplete and never reconstructed. Recognizable credential values are rejected in new configuration input and redacted in historical metadata. Audit is whitelisted metadata, never token or provider-payload storage.

Stop the application with Ctrl+C. Stop the example database with `docker stop pipeline-postgres-dev`; its container and data are disposable and removed on exit. The Flyway history table is ops.pipeline_flyway_schema_history; application schema changes come from Flyway, with Hibernate ddl-auto=validate and open-in-view disabled.

The suite checks PostgreSQL identity/integrity, metadata precision, startup safeguards, incident/origin commit and rollback, the approved lifecycle through application and signed HTTP calls, capability separation, concurrent operators, audit rollback, deterministic IDs, broker duplicates/order/conflicts and commit-before-settlement. External Service Bus message contexts and the explicitly configured GitHub connector facts are simulated; core persistence/transactions and the HTTP pipeline are real. ConnectorUnavailableTests separately checks the normal unavailable connector with a locally signed JWT; no test-only permissive adapter is packaged. The [verification boundaries](docs/architecture.md#verification-boundaries) explain the fixture separation and remaining limits. The older bootstrap/lookup records preserve historical evidence; the Windows wrapper remains untested on Windows.

For the current documentation foundation, check affected links and consistency with the referenced sources, then run:

```bash
git diff --check
```

For TOML configuration changes, run this syntax check with Python 3.11+ (the selected Python baseline supports `tomllib`):

```bash
python3 -c 'from pathlib import Path; import tomllib; [tomllib.loads(p.read_text()) for p in Path(".codex").rglob("*.toml")]; print("TOML syntax OK")'
```

Follow the organization [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [code of conduct](https://github.com/pipesor-org/.github/blob/main/CODE_OF_CONDUCT.md) and [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md). Licensing remains pending.

## Optional Codex setup

Start Codex from this repository root in a trusted project so [repository instructions](AGENTS.md) and [project configuration](.codex/config.toml) are loaded. The [task-based workflow](AGENTS.md#task-based-agent-workflow) is a Pipeline-local pilot; shared backend conventions and other repositories are unchanged. Custom roles inherit permissions; a no-edit instruction is a behavioral constraint, not a separate read-only sandbox.

| Role | Model | Reasoning effort | Use |
|---|---|---|---|
| Primary coordinator | gpt-6.1-sol | medium | Scope, coordination, integration and completion. |
| [service_analyst](.codex/agents/service-analyst.toml) | gpt-6.1-sol | high | Ambiguous requirements, source/approval reconciliation and decision options. |
| Built-in worker | gpt-6.1-sol | high | Implementation and meaningful verification; uses project subagent defaults. |
| [pipeline_reviewer](.codex/agents/pipeline-reviewer.toml) | gpt-6.1-sol | high | Independent domain, authorization, acceptance and test-effectiveness review. |

Keep the limit of two open spawned threads, excluding the primary. Use direct implementation for small clear fixes; do not add a researcher to every task. The built-in explorer remains available for narrow code questions and otherwise inherits project subagent defaults. For a straightforward bounded worker/explorer task, an explicit spawn setting may reduce effort to medium; higher effort requires concrete complexity, such as a disputed invariant or concurrency/security failure. Do not use max/ultra routinely. These are starting choices consistent with IaC, not measured latency or quality guarantees; use an available model if the configured one is unavailable and disclose any substitution.

The coordinator and built-in-role defaults are in .codex/config.toml. Custom role files explicitly pin model and effort and take precedence for those roles; change their files when revising the policy. Do not change global configuration, permissions or providers as part of a role adjustment. See the official [subagent guide](https://learn.chatgpt.com/docs/agent-configuration/subagents) for model/effort precedence and role discovery.

Example request:

> Refine this feature with service_analyst, preserve existing approvals and implement
> the authorized work with worker. Have pipeline_reviewer independently check the
> acceptance criteria and meaningful failure tests, resolve findings and report
> exact verification and remaining dependencies. Do not publish or deploy.

After changing instructions/configuration, start a fresh session. Parsing TOML alone does not establish live discovery. From this trusted repository, inspect model-visible role descriptions without invoking a model:

```bash
codex debug prompt-input 'List the active repository instructions, specialist roles and authorization boundaries.'
```

Check the configured model/effort and thread limit in the TOML files against the table, and confirm service_analyst/pipeline_reviewer descriptions are present alongside the built-in worker/explorer in the prompt diagnostic. Avoid publishing raw diagnostic output, which can include local context. These checks inspect configuration/discovery, not model availability or delegation behavior. For a live pilot in a fresh session, ask the analyst for a bounded no-edit brief and the reviewer to challenge it; then validate implementation handoffs on an authorized feature. See also the official [instruction-discovery guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

Setup verification (2026-11-06, codex-cli 0.167.0): TOML parsing, local file links, whitespace and prompt-input role discovery passed without a model invocation. The separate app-server --print-startup-config diagnostic returned startup configuration unavailable, including outside the read-only sandbox. The CLI also rejects --strict-config for debug; neither strict schema validation nor effective startup/model availability is certified here. No live delegation pilot or application tests were run for this instructions-only change.
