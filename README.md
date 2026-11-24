# pipesor-pipeline-service

Pipeline Service owns monitored workflows, executions, stages, states, metadata and incidents in the `ops` context. Its canonical architecture ID is `pipeline_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The application targets Java 25 / Spring Boot 4.1.2 with Maven Wrapper 3.10.0 and local OpenAPI/Swagger UI (springdoc 3.2.0). Local code implements monitored-workflow registration, inventory/detail, independent policy/ownership updates, per-pipeline effective capabilities, grants/activity queries, operational reads, incident lifecycle and transactional audit. Human operations require both AIOps grants and current verified GitHub access under [ADR-020](docs/architecture.md#approved-github-access-evolution). Domain/application remain framework-free; JPA and SQL adapters implement persistence. The existing opt-in Service Bus consumer, deterministic IDs, delivery semantics and incident transitions are unchanged by this task.

**Platform runtime:** one image runs under the `local` or `azure` profile ([runtime manifest](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/runtime-manifest.md)). Inbound authentication follows the [platform identity contract v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/platform-identity-v1.md): human routes accept session tokens with `Pipeline.Access`; internal routes accept only the workload callers and roles of the route catalog. GitHub facts come from Ingestion (`/internal/v1/github/**`) with a Pipeline workload token plus the user's session token as subject token; when Ingestion is unavailable human operations fail with 503 and never fall back to grants. Pipeline holds no GitHub tokens. See [routes and configuration](docs/runtime.md).

## Documentation

- [Repository lookup increment](docs/implementation-catalog-query.md): implemented layers, migration scope, integration tests and remaining contracts.
- [Bootstrap implementation record](docs/implementation-bootstrap.md): scope, dependency selection, acceptance criteria and actual verification results.
- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [First implementation agreement](docs/architecture.md#first-implementation-agreement): package structure, handlers, persistence, identity, audit metadata and OpenAPI conventions.
- [Runtime: profiles, configuration and routes](docs/runtime.md): environment variables, route catalog, internal contracts and health.
- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Shared CI/CD guide](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md): reusable principles, versioning, future promotion and adoption boundaries for Java, Python and SPA.
- [Deployment contract](docs/deployment-contract.md): development baseline, service-specific delivery requirements and unresolved contracts.
- [Rollback implementation and runbook](#rollback-implementation-and-runbook): explicit workflow operation, provenance checks, recovery triggers and acceptance criteria.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the toolchain policy. Run commands from this repository root with JDK 25. Tests now require a working Docker daemon: Spring Boot/Testcontainers starts disposable PostgreSQL 17.11 instances, applies Flyway and validates JPA. First execution needs HTTPS access to Maven Central and the image registry plus writable caches. No Azure access or existing database credentials are required for tests. Missing Docker fails the integration suite.

Commit-message tooling uses Bun **1.4.4** and the standalone Lefthook **2.2.2+** binary on `PATH`, matching the existing workspace/frontend/landing setup. These are development tools; Maven and the application runtime remain independent. Install dependencies from the committed lockfile, validate the hook configuration and install the hook once per clone:

```bash
bun install --frozen-lockfile
lefthook validate
bun run setup:hooks
bun run commitlint --last
```

[lefthook.yml](lefthook.yml) runs commitlint on `commit-msg`; [commitlint.config.js](commitlint.config.js) is also used directly by CI. The conventional preset defines types, syntax and breaking-change parsing; repository overrides require headers of at most 72 characters and lowercase scopes, disable body/footer line-length limits and validate messages normally ignored by commitlint (including merge messages). PR titles and full squash messages use the same configuration, PR descriptions must be nonempty, and all PR commits are checked as a Git range. The imperative English wording and meaningful migration/verification details still require review. Local hooks can be bypassed, so CI repeats validation. Dependency installation does not install hooks automatically. See the official [Lefthook/commitlint integration](https://lefthook.dev/examples/commitlint/).

Compile, run the domain and integration tests and package the executable JAR:

```bash
./mvnw -B -ntp clean verify
```

The same verification includes `OperationalReferenceTests`, which checks late
owner records, committed reference contradictions and authority-before-read
behavior using disposable PostgreSQL and an explicit test machine authority.

Tests boot the real application under the `local` profile with locally generated platform keys (`TestIdentity`), real PostgreSQL and real HTTP; only key discovery, the GitHub connector facts (`GitHubConnectorDouble`) and Service Bus SDK message contexts are simulated. `IngestionGitHubConnectorTests`, `HttpCaptureScopeSyncTests` and `OwnerValidationHttpTests` exercise the real outbound clients against loopback stand-ins. Integration tests disable the capture scope reconciler (`pipeline.capture-scope.reconcile-enabled=false`); `CaptureScopeSyncTests` records the post-commit calls while Ingestion stays unreachable.

For an interactive local run use the shared stack from `pipesor-workspace` (PostgreSQL on 127.0.0.1:55432, Service Bus emulator on 5672) and its generated environment file:

```bash
./mvnw -B -ntp package -DskipTests
set -a; . ~/.config/pipesor-local/env/pipeline.env; set +a
./mvnw -B -ntp spring-boot:run
curl -s http://127.0.0.1:8082/actuator/health/readiness
curl -s http://127.0.0.1:8082/.well-known/jwks.json
```

Flyway migrates `ops` (history `ops.pipeline_flyway_schema_history`) and creates `audit.audit_events` if absent at every startup; Hibernate only validates. The `local` profile additionally enables OpenAPI at `/v3/api-docs` and Swagger UI. Configuration, routes and profile differences are listed in [docs/runtime.md](docs/runtime.md).

| Setting | Default | Use |
|---|---|---|
| `PIPESOR_ENDPOINTS_INGESTION` (`pipeline.ingestion.base-uri`) | required | Ingestion base URL; also used for `PUT /internal/v1/capture/workflow-scopes/{repositoryId}/{workflowId}` with role `Pipeline.CaptureScope.Write` |
| `PIPESOR_CAPTURE_SCOPE_RECONCILE_INTERVAL` (`pipeline.capture-scope.reconcile-interval`) | `5m` (min 10 s) | capture scope reconciliation period after readiness |
| `PIPESOR_CAPTURE_SCOPE_RECONCILE_ENABLED` (`pipeline.capture-scope.reconcile-enabled`) | `true` | disable the reconciliation schedule (tests) |
| `pipeline.capture-scope.page-size` | `100` | pipelines read per reconciliation page |

The frontend-facing pipeline responses consistently include repositoryId, githubRepositoryId, fullName, defaultBranch, workflow identity/name/file, operational settings, team references, createdAt and the current user's effectiveCapabilities. VIEW requires GitHub read; OPERATE requires read; CONFIGURE requires administration. CONFIGURE and OPERATE each imply VIEW but remain independent. Registration creates CONFIGURE, not OPERATE. Stored grants never override lost GitHub access, including historical child resources.

| Endpoint | Behavior and required authorization |
|---|---|
| POST /api/v1/pipelines | Verified linked identity, tenant-admitted installation, effective read and repository administration; 201 with Location. Metadata comes from the connector, not frontend names/paths. After commit the capture scope is pushed to Ingestion (ADR-023); a failed push keeps the 201 and is repaired by reconciliation. |
| GET /api/v1/pipelines; GET /api/v1/pipelines/{id} | AIOps VIEW plus current GitHub read; repository eligibility and grants are combined before pagination. |
| GET /api/v1/pipelines/registrations?repositoryId= | Current GitHub read of the admitted repository; no AIOps grant needed (ADR-028). Existence only: `{repositoryId, items[{pipelineId, githubWorkflowId, accessible}]}` ordered by numeric workflow id, so a reader without grants can see that a workflow is monitored and ask for access. `accessible` is the caller's effective VIEW. |
| GET /api/v1/incidents?state=OPEN\|RESOLVED&pageSize&pageToken&filters | Cross-pipeline queue, newest first, filtered by grants and current GitHub read and then by the optional ADR-027 filters before paging. Items match `/pipelines/{id}/incidents` and include repository, workflow and diagnosis summary; response `{items, nextPageToken, total, appliedFilters}`. See [docs/runtime.md](docs/runtime.md#incident-queue). |
| GET /api/v1/incidents/summary; GET /api/v1/insights | Counts per severity/status/diagnosis of the same filtered set, and History Insights over visible resolved incidents (ADR-027). |
| PUT /api/v1/pipelines/{id}/settings | Existing complete replacement retained: mandatory criticality/monitoringStatus; omitted or null optional fields clear their values. |
| PUT /api/v1/pipelines/{id}/policy | Replace criticality, monitoringStatus and environment. All three keys must be present; environment:null clears it. Preserve ownership, including concurrent updates. |
| PUT /api/v1/pipelines/{id}/ownership | Replace responsibleTeamRef and responsibleTeamNameSnapshot. Both keys must be present; explicit null clears that value. Preserve policy, including concurrent updates. No pipeline owners are introduced. |
| PUT /api/v1/pipelines/{id}/grants/{principalRef}/{capability}; DELETE counterpart | CONFIGURE plus current administration. Granting additionally checks the recipient's link/access, and administration for CONFIGURE. Revocation does not require the recipient to remain connected. Revoking the pipeline's last CONFIGURE grant, also one's own, is `409 LAST_CONFIGURE_GRANT` (ADR-028). |
| GET /api/v1/pipelines/{id}/grants | CONFIGURE plus current administration. Lists stored grant identities/capabilities, not GitHub roles or user profiles. |
| GET /api/v1/pipelines/{id}/activities | CONFIGURE plus current administration. Sanitized pipeline-only audit; historyComplete=false. Old missing fields remain absent. |

Every settings/section update also requires CONFIGURE plus current GitHub administration. A committed `monitoringStatus` change is pushed to Ingestion's capture scope after the transaction, like registration. Executions, stages, incidents, incident runs and human incident commands resolve their owning repository and apply the same cumulative checks; cross-pipeline verification checks both resources. Missing GitHub linkage returns 403 with GITHUB_CONNECTION_REQUIRED, denied access returns 403 and unavailable/expired/misbound verification returns 503. Connector results may be reused only inside one HTTP request, never positively cached between requests.

Lists retain offset=0 and limit=25 defaults; offset is a nonnegative integer and limit is an integer from 1 to 100. Policy/ownership audit snapshots now include all nullable configuration fields (snapshotVersion=2); earlier records are incomplete and never reconstructed. Recognizable credential values are rejected in new configuration input and redacted in historical metadata. Audit is whitelisted metadata, never token or provider-payload storage.

Stop the application with Ctrl+C. Stop the example database with `docker stop pipeline-postgres-dev`; its container and data are disposable and removed on exit. The Flyway history table is ops.pipeline_flyway_schema_history; application schema changes come from Flyway, with Hibernate ddl-auto=validate and open-in-view disabled.

The suite checks PostgreSQL identity/integrity, metadata precision, startup safeguards, incident/origin commit and rollback, the approved lifecycle through application and signed HTTP calls, capability separation, concurrent operators, audit rollback, deterministic IDs, broker duplicates/order/conflicts and commit-before-settlement. External Service Bus message contexts and the explicitly configured GitHub connector facts are simulated; core persistence/transactions and the HTTP pipeline are real. ConnectorUnavailableTests checks the real Ingestion connector with Ingestion unreachable (503); no test-only permissive adapter is packaged. The [verification boundaries](docs/architecture.md#verification-boundaries) explain the fixture separation and remaining limits. The older bootstrap/lookup records preserve historical evidence; the Windows wrapper remains untested on Windows.

For the current documentation foundation, check affected links and consistency with the referenced sources, then run:

```bash
git diff --check
```

For TOML configuration changes, run this syntax check with Python 3.11+ (the selected Python baseline supports `tomllib`):

```bash
python3 -c 'from pathlib import Path; import tomllib; [tomllib.loads(p.read_text()) for p in Path(".codex").rglob("*.toml")]; print("TOML syntax OK")'
```

Follow the organization [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [code of conduct](https://github.com/pipesor-org/.github/blob/main/CODE_OF_CONDUCT.md) and [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md). Licensing remains pending.

## CI/CD

[CI](.github/workflows/ci.yml) calls the shared [`service-ci.yml`](https://github.com/pipesor-org/pipesor-ci#catalog)
from [pipesor-ci](https://github.com/pipesor-org/pipesor-ci), pinned by commit SHA (Dependabot proposes updates):

1. **Conventions**: canonical commitlint/Lefthook configuration, PR title (at most 65 characters) and description,
   Conventional Commits for the PR range and for the integrated `main` commit, actionlint and zizmor.
2. **Build**: the tooling tests and `./mvnw -B -ntp clean verify`, then one `linux/amd64` image build tagged
   `ghcr.io/pipesor-org/pipesor-pipeline-service:sha-<commit>-<run>-<attempt>`, the shared OCI revision/non-root checks and
   `tools/check_image.py`.
3. **Image assurance**: Trivy SBOM and vulnerability report (90-day evidence); fixable HIGH/CRITICAL vulnerabilities
   block. Reviewed exceptions go in `.trivyignore.yaml`.
4. **Publish** (pushes to `main` only): the exact tested archive is pushed to the private GHCR package without
   rebuilding and `candidate-<run>-<attempt>/candidate.json` records the source SHA, image, digest and CI run/attempt.
   Use **Re-run all jobs** after a publication failure; tags and artifacts include the attempt.

PRs are verified and scanned but never published. A ruleset protects `main`: changes arrive through squash-merged PRs.

The image check verifies user `10001:10001`, rejection without runtime configuration, migrations/startup with disposable PostgreSQL under the `local` profile with a disposable signing key, readiness and HTTP 401 for an unauthenticated business request. It creates uniquely named local containers/network, publishes only a random localhost port and removes its resources on exit. Its synthetic credentials, signing key and `local` profile are test setup. Container defaults bind `0.0.0.0:8080`; they do not approve cloud ingress, probes or a production migration mechanism. The base image is pinned by digest; dependency/base-image updates require re-verification. The Docker context includes only the packaged JAR and Dockerfile.


Local checks before opening a PR (Java 25, Python 3, Bun 1.4.4, Docker):

```bash
bun install --frozen-lockfile && bun run setup:hooks
python3 -m unittest discover -s tools -p 'test_*.py'
./mvnw -B -ntp clean verify
revision="$(git rev-parse HEAD)"
docker build --platform linux/amd64 --build-arg "VCS_REF=$revision" --tag pipesor-pipeline-service:local-ci .
GITHUB_SHA="$revision" python3 tools/check_image.py pipesor-pipeline-service:local-ci
```

**Deployment.** This repository does not deploy. The environment's Terraform root in
[pipesor-iac](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/dev-runtime-deployment.md) pins this service's
image by digest. Promote a candidate by using its `candidate.json` digest in the next environment's reviewed plan;
roll back with a new reviewed plan for the earlier digest.

**Releases.** [Release candidate](.github/workflows/release.yml) is manual and hard-disabled in source. It uses the
shared [release action](https://github.com/pipesor-org/pipesor-ci/tree/main/actions/release) to name an existing
candidate digest `vX.Y.Z[-rc.N]` with a GitHub Release, never rebuilding; stable requires retained preprod validation
for the same digest. See the [shared CI/CD guide](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md).

**Registry retention.** [Registry retention](.github/workflows/registry-retention.yml) runs weekly through the shared [pipesor-ci workflow](https://github.com/pipesor-org/pipesor-ci#registry-retention): it keeps every deployed or recently deployed digest (pipesor-iac `images.json` history), release digests, the newest and recent candidates and the children of kept image indexes, and reports the rest. It is a dry run until the repository variable `REGISTRY_RETENTION_DELETE` is `true`.

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

### Local workflow registration browser pilot

The opt-in `WorkflowRegistrationBrowserTest` starts the existing Pipeline API twice against retained disposable PostgreSQL. It uses the signed delegated test decoder and explicit GitHub facts double from the HTTP contract tests. It exercises real registration, list, detail and policy requests, checks denial and duplicate behavior, and confirms that registration creates no incident. See the [frontend advisor runbook](https://github.com/pipesor-org/pipesor-frontend/blob/main/docs/local-product-mvp.md#workflow-registration-pilot) for the browser verifier and setup.
