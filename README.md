# pipesor-pipeline-service

Pipeline Service owns monitored workflows, executions, stages, states, metadata and incidents in the `ops` context. Its canonical architecture ID is `pipeline_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The application targets Java 25 / Spring Boot 4.1.2 with Maven Wrapper 3.10.0 and local OpenAPI/Swagger UI (springdoc 3.2.0). Local code implements monitored-workflow registration, inventory/detail, independent policy/ownership updates, per-pipeline effective capabilities, grants/activity queries, operational reads, incident lifecycle and transactional audit. Human operations require both AIOps grants and current verified GitHub access under [ADR-020](docs/architecture.md#approved-github-access-evolution). Domain/application remain framework-free; JPA and SQL adapters implement persistence. The existing opt-in Service Bus consumer, deterministic IDs, delivery semantics and incident transitions are unchanged by this task.

**Connector integration is blocked:** ADR-020 is integrated in pipesor-docs main, but no executable remote connector contract is available. Pipeline defines only the minimal in-process GitHubConnector port. Normal execution uses an unavailable adapter: dependent human operations fail with 503 instead of falling back to grants. There is no permissive local profile. Automated tests supply explicit test-only facts and real PostgreSQL/HTTP; they are not live GitHub/Ingestion integration. No GitHub tokens, OAuth, discovery, webhooks or GitHub HTTP client belong to Pipeline. See [current local implementation and boundaries](docs/architecture.md#adr-020-local-implementation-and-boundaries). This CI/CD increment does not change that connector integration boundary.

## Documentation

- [Repository lookup increment](docs/implementation-catalog-query.md): implemented layers, migration scope, integration tests and remaining contracts.
- [Bootstrap implementation record](docs/implementation-bootstrap.md): scope, dependency selection, acceptance criteria and actual verification results.
- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [First implementation agreement](docs/architecture.md#first-implementation-agreement): package structure, handlers, persistence, identity, audit metadata and OpenAPI conventions.
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

## CI, images and disabled delivery

[CI](.github/workflows/ci.yml) checks Conventional Commit titles/commits with commitlint and a nonempty PR description, runs the Java/PostgreSQL suite, then builds and checks the packaged container. Short-lived branches target `main`; maintainers squash with the validated PR title. PRs never publish. Pushes to `main` repeat verification and pass the **same tested image archive** to a separate GHCR publication job with `packages: write`. Actions are pinned by commit; PR jobs have no publication permission. CI checks can report failure on GitHub Free/private, but this plan provides no enforceable required reviews/checks or independent deployment approvals: maintainers must follow the review/merge procedure.

Run the local delivery checks after `./mvnw -B -ntp clean verify`:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
bun run test:release
docker build --platform linux/amd64 --build-arg VCS_REF="$(git rev-parse HEAD)" --tag pipesor-pipeline-service:local-ci .
python3 tools/check_image.py pipesor-pipeline-service:local-ci
```

The image check verifies user `10001:10001`, rejection without datasource configuration, migrations/startup with disposable PostgreSQL and HTTP 401 for an unauthenticated business request. It creates uniquely named local containers/network, publishes only a random localhost port and removes its resources on exit. Its synthetic credentials/shared-audit fixture and `local` migration profile are test setup. Container defaults bind `0.0.0.0:8080`; they do not approve cloud ingress, probes or a production migration mechanism. The base image is pinned by digest; dependency/base-image updates require re-verification. The Docker context includes only the packaged JAR and Dockerfile.

Local workflow validation uses **act 0.2.97** plus **actionlint 1.7.12**. Install the official Linux x86-64 release binaries into ignored `.tools/bin`, checking the published checksums (the tools are development-only):

```bash
mkdir -p .tools/bin
gh release download v0.2.97 --repo nektos/act --pattern act_Linux_x86_64.tar.gz --pattern checksums.txt --dir .tools/downloads/act --clobber
(cd .tools/downloads/act && sha256sum --ignore-missing --check checksums.txt) && tar -xzf .tools/downloads/act/act_Linux_x86_64.tar.gz -C .tools/bin act
gh release download v1.7.12 --repo rhysd/actionlint --pattern actionlint_1.7.12_linux_amd64.tar.gz --pattern actionlint_1.7.12_checksums.txt --dir .tools/downloads/actionlint --clobber
(cd .tools/downloads/actionlint && sha256sum --ignore-missing --check actionlint_1.7.12_checksums.txt) && tar -xzf .tools/downloads/actionlint/actionlint_1.7.12_linux_amd64.tar.gz -C .tools/bin actionlint
```

From WSL2 with Docker Engine running, execute only the CI verification job:

```bash
.tools/bin/actionlint -shellcheck= .github/workflows/*.yml
timeout 30m .tools/bin/act push \
  -W .github/workflows/ci.yml -j verify -e .github/act/push.json \
  -P ubuntu-latest=catthehacker/ubuntu:act-24.04@sha256:4f2d5083a9d10d018c1c511eb8665cd480553c11975e78fd903a46daa830768b \
  --container-architecture linux/amd64 --network host \
  --env TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  --env-file /dev/null --secret-file /dev/null --var-file /dev/null --input-file /dev/null \
  --no-cache-server --rm
```

The explicit workflow/job and synthetic event restrict this run to `verify`. act's `ACT=true` skips Maven caching, image-transfer artifacts and report uploads; the publisher also rejects act before any registry operation, and deployment retains its source-level disable. No GitHub/Azure tokens, real `.env` or secrets files are supplied. Do not run publication/deployment jobs locally. The pinned medium runner image is a local approximation, not the hosted runner. Linux host networking lets Testcontainers and the image smoke check reach disposable ports on the WSL Docker host. Run only reviewed local workflows: Docker socket access can control that daemon. `.tools` is ignored and not copied into act's workspace; act's action cache and pulled images can remain for reuse.

act exercises step execution, contribution validation, Maven/PostgreSQL tests, packaging and the Docker image smoke check against the **local working tree**, including uncommitted changes. The synthetic event selects local push mode; commitlint reads the actual local HEAD message. Uncommitted files can differ from that commit, and this does not prove a remote PR/merge is valid. It does not validate GitHub's artifact/cache services, token permissions, concurrency, cancellation, hosted runner parity or OIDC/RBAC. Reserve those checks, actual PR metadata/ancestry and GHCR publication for separately authorized GitHub Actions runs; Azure behavior remains pending. The host `timeout` bounds the local run because act does not enforce job timeouts. `actionlint` checks workflow syntax/expressions; `-shellcheck=` explicitly omits the unavailable ShellCheck integration. See [act usage](https://nektosact.com/usage/index.html), [runner differences](https://nektosact.com/usage/runners.html) and [unsupported behavior](https://nektosact.com/not_supported.html).

The small Python suite protects delivery behavior: rejection before Azure writes, digest/target selection and readiness handling, plus cleanup after a timed-out Docker client. Commit-message validation uses commitlint exclusively; its former Python validator and tests have been removed. Keep the distinction between these focused delivery tests, real PostgreSQL/container smoke checks and act's workflow execution. No duplicate implementation or test suite of commitlint's parser is maintained here.

Each published candidate uses `ghcr.io/<lowercase repository>:sha-<commit>-<run>-<attempt>`. The authoritative identity is `ghcr.io/<repository>@sha256:<digest>`, not the mutable registry tag or Maven's internal `0.0.1-SNAPSHOT`. `candidate-<run>-<attempt>/candidate.json` records schema version 1, `source_sha`, `image`, `digest`, `build_run_id` and `build_run_attempt`. Integration does not assign versions. The separately prepared release workflow can name selected candidates; its publication is source-disabled. Existing staging/release policies are unchanged; no QA environment is required for the MVP. A future promotion must reuse the digest without rebuilding; cross-environment Build Once, Deploy Many has not been demonstrated.

Image-transfer artifacts expire after one day; test reports after 14 days; candidate and delivery artifacts after 90 days (subject to repository limits). These limits do not delete GHCR images. For a publication failure, use **Re-run all jobs**, not failed jobs only: artifact names bind the attempt. A retry builds a new candidate; compare digests instead of assuming equality. Releases preserve `candidate.json` as a GitHub Release asset independently of Actions artifact expiry. Keep the Release, original CI run/attempt and registry digest while deployed or needed for recovery. The original attempt API is used, so a later failed rerun does not invalidate an earlier successful candidate. Deleting the producer run can still remove required provenance.

Configured registry behavior retains all GHCR image versions and release assets; **no automatic deletion is implemented**. The local retention preview below identifies old unselected technical candidates while preserving release, environment and recovery protections. It has no deletion capability or credentials. Review storage/Actions quotas as usage grows; any later cleanup needs a separately reviewed protection policy and explicit authorization. Repository writers can alter/delete releases or registry tags; Free/private procedural review remains the control, not immutable-release enforcement. CI uses its repository `GITHUB_TOKEN`, with package creation/access policy checked before first hosted publication. The deliverable is the container; the internal JAR is not published as a separate Maven library.

Preview retention locally from a complete, flattened GitHub Packages container-version inventory and an operator-verified protection snapshot. Store snapshots under ignored `.delivery/`; neither the tool nor this task retrieves live inventory. Example protection schema (digests below are placeholders):

```json
{
  "schema_version": 1,
  "image": "ghcr.io/pipesor-org/pipesor-pipeline-service",
  "captured_at": "2026-11-08T12:00:00Z",
  "environments": {
    "dev": { "verified": true, "digests": ["sha256:<64 lowercase hex>"] }
  },
  "releases": { "verified": true, "digests": ["sha256:<64 lowercase hex>"] }
}
```

```bash
python3 tools/retention.py --image ghcr.io/pipesor-org/pipesor-pipeline-service \
  --inventory .delivery/versions.json --protection .delivery/protection.json \
  --age-days 30 --required-environment dev > .delivery/retention-preview.json
```

The protection snapshot must be at most 24 hours old. Its environment lists must cover active, in-flight, selected-for-promotion and rollback digests; `releases` must cover all published RC/stable manifests. Empty lists assert verified absence, not unknown state. Include every required environment explicitly when the footprint expands; extra supplied scopes are protected too. The default preview proposes only candidates at least 30 days old whose tags are exclusively the exact technical SHA/run/attempt format, keeps the newest ten technical entries, and preserves protected, recent, untagged or unrecognized versions. Missing/unverified/stale evidence rejects the preview. Thirty days and ten candidates are reviewable preview defaults, not approved registry deletion or evidence-retention periods. The tool cannot establish inventory completeness, operator truth or changes after the snapshot; **preview_only is not deletion authorization**. Do not feed its output directly into a deletion command. Keep producer CI evidence with retained releases. See the [shared promotion and evidence contract](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md#future-environment-promotion-to-be).

[Release candidate](.github/workflows/release.yml) is implemented but **hard-disabled**. It accepts either a successful original main CI run/attempt or a retained published Release, the exact image confirmation and an issue/PR authorization record. It verifies CI provenance and ancestry, computes a version, creates a new Git tag and a draft GitHub Release with the candidate manifest, then publishes it. It does not build, push registry aliases or change the deployed app. The SemVer-to-image mapping lives in the Release's `candidate.json`; the GHCR digest remains the artifact identity.

The [shared versioning convention](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md#versiones-paquetes-y-retención) distinguishes technical candidates from selected RC/stable releases. Operationally, supply an explicit `version` before the first stable tag and while the stable base is 0.x; omit it after a 1.x+ stable base. A retained RC keeps its core X.Y.Z when selected for stable. Existing tags are never overwritten. Stable requires the [proposed preprod validation contract](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md#candidate-validation-versus-active-deployment); no producer/environment is commissioned, so dev-only operation cannot publish stable. RC needs no deployed environment and is never assigned automatically per integration. Hotfix release branches are not implemented.

Plan locally using an actual retained candidate and its original CI-attempt metadata:

```bash
GITHUB_REPOSITORY=pipesor-org/pipesor-pipeline-service bun tools/release.mjs plan \
  --candidate .delivery/candidate/candidate.json --run .delivery/candidate-run.json \
  --kind rc --version 0.1.0
```

`0.1.0` is an example development target, not an assigned version. Supply `version` while no stable base exists or the stable base is 0.x; omit it after a 1.x+ stable release. Planning writes only local plan/manifest/notes files. Tests use synthetic candidates and temporary Git history, never counterfeit published CI evidence. Activating publication requires separate reviewed removal of the source disable, `RELEASE_ENABLED=true`, publication authorization and hosted GitHub verification. If publication stops after creating a tag/draft, inspect the exact tag, asset and plan before retry; never move an existing version or delete evidence automatically. The tag/draft/publish operations are not an atomic transaction.

[Deploy dev](.github/workflows/deploy.yml) is **hard-disabled by `false` in the job condition**. No variable or dispatch can activate it as committed. Its prepared adapter selects either a CI run/attempt artifact or a published RC/stable Release manifest (never both), checks the successful historical main CI attempt, release tag/source consistency and source ancestry, validates configuration and explicit target/digest confirmations **before OIDC login**, then updates only the image of one existing single-container, single-revision ACA app. It never builds, uses `az containerapp up`, runs Terraform, creates apps or configures runtime secrets. Repeating the same digest does not require a new revision. `aca_ready` records control-plane readiness, not functional acceptance, ingress health or database recovery.

Activation requires all of the following, in order:

1. Satisfy [IaC ADR 004](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-004-mvp-state-and-deployment.md) and the [delivery contract](docs/deployment-contract.md), including required service readiness/integration, runtime/database/migration/probe and private GHCR pull contracts, cost review and explicit Azure target/action authorization. Validate ADR 004's isolated one-job Debian VM runner (label `pipesor-dev-deploy`), current runner/action support, Python 3, Git, GitHub CLI, Azure CLI 2.92.0 and network access before registration; discard guest changes after each trusted deployment job. PR/build jobs remain hosted. This workflow does not authorize creating or registering that runner. D03/D03-EXP are **not approved**; V1–V5 and the experimental exception are suspended. A working local image does not waive readiness.
2. Accept the application/infra ownership contract before using this native adapter: IaC owns foundations; the chosen app owner provisions/configures the existing app; this workflow updates its image only. Prevent Terraform and this adapter from both reconciling that field, and coordinate provisioning/configuration/recovery writes. The local native adapter is prepared, not adoption of a universal deployment standard or an ADR amendment. If Terraform image ownership is retained, keep this workflow disabled and replace its adapter under review.
3. Supply repository variables `ACA_RESOURCE_ID`, `ACA_ENVIRONMENT_ID`, `ACA_CONTAINER_NAME`, `AZURE_SUBSCRIPTION_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, plus `ACA_IMAGE_OWNER=native` and `ACA_DEPLOYMENT_ENABLED=true`. These are identifiers/acknowledgements, not secrets or proof of cloud authorization. IDs must match the approved subscription and app/environment. No default target is embedded.
4. Separately establish an Azure deployment identity with OIDC trust restricted to this repository's `refs/heads/main`, audience `api://AzureADTokenExchange`, and least-privilege Azure RBAC scoped to the existing app. OIDC authenticates; RBAC authorizes. App runtime identity and GHCR private-pull credentials are separate. An app-write grant can change more than its image; the pipeline's narrower behavior is not field-level RBAC. Do not grant subscription Contributor, role assignment or infrastructure creation merely for delivery.
5. Review and remove the source-level disable only after explicit activation authorization; publish that reviewed change separately. Dispatch from `main` with either candidate run/attempt or `candidate_release`, exact digest/target confirmations and a repository issue/PR URL documenting authorization. The URL is a procedural record, **not an independently enforced approval**. A repository writer can alter workflows, so two-person review remains a team control on Free/private. Validate hosted artifact download, OIDC, GHCR pull and ACA readiness in the first separately authorized Azure run.

Delivery jobs serialize per service without cancellation. This lock does not coordinate IaC or manual operators; arrange exclusive target access before activation. Azure login already requires a host CLI, so the adapter uses that qualified CLI directly (version 2.92.0) with the runner's Git rather than introducing a second CLI container. Image ownership/configuration agreements and ADR 004 remain activation prerequisites.

Recovery is operator-triggered through the implemented rollback operation in the same guarded deployment path. Before updating ACA, the adapter writes and fsyncs `deployment-before.json` with the previous image/revisions and validated target/candidate references. It writes `deployment-result.json` for success or failure; the workflow attempts to upload both plus the validated request even when preflight, login or readiness fails. Records contain no full Azure response, runtime secret values or raw CLI output. Abrupt runner loss can still prevent upload; local persistence is not an independently backed-up recovery store.

### Rollback implementation and runbook

**Status: explicit operator-triggered rollback implemented and tested locally; source-disabled and unverified in Azure.** [deploy.yml](.github/workflows/deploy.yml) offers `operation=deploy|rollback` and uses the same serialized image-only adapter for both operations. There is no duplicate `rollback.yml` and rollback is not triggered automatically on failure. Scope is the existing dev Container App after all activation prerequisites. Commands below are instructions for a later authorized incident, not commands executed here.

**When to use it:** an image update/readiness failure or an observed functional regression after an apparently ready deployment. CI/image-publication failure alone does not imply a deployed application changed. The operator selects a known-good candidate and an earlier successful delivery run. The rollback code verifies that delivery's ready receipt and producer before Azure login and again before the update; it does not automatically discover the last good deployment or certify functional/database compatibility.

1. **Freeze competing writes and identify the incident.** Record the failed/suspect run URL, source candidate, exact dev target and observed symptoms in the repository issue/PR. Review queued/in-progress application jobs and coordinate with the IaC/configuration owner; service concurrency alone does not prevent an infrastructure or manual write. Do not cancel an in-progress Azure operation blindly or let a queued deployment immediately undo the recovery.
2. **Preserve and inspect evidence.** Download the failed run's artifact `deployment-dev-<run>-<attempt>`. Keep `deployment-before.json`, `deployment-result.json` and the validated request if available. The before record contains the image/revisions observed before mutation; a failed update response does not prove no mutation occurred. If failure happened before mutation, inspect actual state before deciding any redeployment is necessary. Missing evidence after runner loss is an uncertainty to resolve, not permission to guess a digest.

```bash
gh run download "$FAILED_RUN_ID" \
  --name "deployment-dev-${FAILED_RUN_ID}-${FAILED_RUN_ATTEMPT}" \
  --dir .delivery/rollback/failed
```

3. **Inspect the authorized target read-only.** Use its exact previously approved resource ID, tenant/subscription and the qualified Azure CLI. Compare the current image and latest/ready revisions to the failed run's evidence. This query reports identifiers/state only, not runtime configuration or secrets:

```bash
az containerapp show --ids "$ACA_RESOURCE_ID" \
  --query '{id:id,image:properties.template.containers[0].image,state:properties.provisioningState,revision:properties.latestRevisionName,ready:properties.latestReadyRevisionName}' \
  --output json
```

4. **Select and qualify the recovery candidate A.** Locate the prior successful delivery record for that same target, plus its functional acceptance evidence. Set `rollback_run_id` and `rollback_run_attempt` to **that earlier successful deploy.yml delivery**, not the failed B delivery and not A's CI build run. The code fetches the historical delivery attempt, requires successful main `workflow_dispatch` execution of this repository's deploy.yml, and binds its version-1 receipt to `delivery_run_id`, `delivery_run_attempt` and `delivery_workflow_sha`. That tooling SHA can differ from the application's `source_sha`. It then requires `status=aca_ready`, a recorded ready revision and exact equality of repository, image/digest, app target, ACA environment, container and original candidate CI source/run/attempt. Failed, unrelated, unversioned or mismatched receipts are rejected before Azure; the earlier recorded revision is a reference, not a promise to reactivate that same revision.

Recover A's Release manifest or original successful CI run/attempt artifact, confirm the image still exists and retain provenance. The ordinary CI/Release checks remain mandatory independently of the delivery receipt. If the CI artifact expired, a retained Release can supply the manifest, but the original CI attempt must still be verifiable. A mutable bootstrap tag without a valid prior delivery is not an eligible rollback origin.

5. **Check compatibility and authorize.** The responsible owners must confirm A works with current schema, configuration, secret versions and dependencies. Missing/unknown compatibility means stop; no SQL downgrade, Terraform-state restore or secret/configuration rollback is implied. Record the exact target, digest, candidate/run reference, compatibility evidence and explicit recovery authorization in the issue/PR referenced by `authorization_ref`, within ADR 004's accepted dev operator/access boundaries. Set `rollback_confirmed=true` only after that review. The boolean and URL are procedural operator assertions, not automated functional/schema checks or independently enforced approval on GitHub Free/private. If A is already running and the required checks pass, document that recovery required no additional update.

6. **Dispatch a new recovery run when an update is needed.** Use Actions → **Deploy dev (disabled)** → Run workflow from `main` after separately authorized activation. Choose `operation=rollback`, the earlier good delivery's `rollback_run_id`/`rollback_run_attempt`, and `rollback_confirmed=true`. Select **either** `candidate_release` **or** the candidate's CI `candidate_run_id` + `candidate_run_attempt`, never both; fill `confirm_image`, `confirm_target` and `authorization_ref` for A. Ordinary `operation=deploy` rejects stray rollback inputs. Do not rerun the failed B run: it still selects B. For a retained Release, the equivalent authorized CLI dispatch is:

```bash
gh workflow run deploy.yml --ref main \
  -f operation=rollback \
  -f rollback_run_id="$KNOWN_GOOD_RUN_ID" \
  -f rollback_run_attempt="$KNOWN_GOOD_RUN_ATTEMPT" \
  -f rollback_confirmed=true \
  -f candidate_release="$RECOVERY_RELEASE" \
  -f confirm_image="$RECOVERY_IMAGE" \
  -f confirm_target="$ACA_RESOURCE_ID" \
  -f authorization_ref="$AUTHORIZATION_REF"
gh run watch "$RECOVERY_RUN_ID" --exit-status
```

`KNOWN_GOOD_RUN_ID`/`KNOWN_GOOD_RUN_ATTEMPT` identify A's earlier successful delivery. `RECOVERY_RELEASE` is an existing `vX.Y.Z-rc.N` or `vX.Y.Z`; `RECOVERY_IMAGE` is its exact `ghcr.io/<repository>@sha256:<digest>`. Obtain `RECOVERY_RUN_ID` from the newly dispatched run in Actions, not the failed run. For a CI artifact, replace the release field with the original successful run ID and attempt. No version/tag/RC or Git revert is created, and no image is rebuilt. While the source disable remains, dispatch skips the job and cannot recover an app.

7. **Verify before closing.** Inspect the new run's result: `operation=rollback`, expected target and A's digest, selected `rollback_run_id`/attempt/revision, new `delivery_run_id`/attempt/workflow SHA and `status=aca_ready`; confirm the latest revision equals the ready revision. The newly ready revision may differ from the historical `rollback_revision`. Then execute the service's separately approved authenticated functional/smoke checks and monitoring observation; pipeline success/ACA readiness alone is not functional acceptance. No public `/health` route or monitoring interval is invented by this runbook; those remain activation contracts. Preserve the recovery run URL, result, functional evidence and incident outcome. Retain both the failed and recovery run records and protect A in the retention snapshot before releasing the deployment freeze.

**Stop and escalate:** no prior verifiable candidate; deleted image/producer evidence; uncertain schema/configuration compatibility; identity/target mismatch; competing owner writes; recovery failure; or absent required functional validation. Use an explicitly reviewed forward fix or provisioning/data recovery owned by the relevant maintainers. Do not bypass provenance/authorization checks, move an existing version tag, substitute an arbitrary image, restore Terraform state as application rollback or expose secrets in incident evidence.

Local coverage runs the CLI through A ready → B update failure → explicit `operation=rollback` to A, with independently verified candidate CI and delivery metadata and separate failure/recovery receipts. Invalid prior receipts/producer identity/target/digest or missing confirmation reject in both validate and deploy modes before any Azure call. Pre-update evidence and readiness/storage failures remain covered. Azure/CI responses are simulated in these tests. Rollback is not triggered automatically; there is no database downgrade, traffic splitting or implementation of recovery for future qa/preprod/prod; those destinations keep the TO-BE controls. Abrupt runner loss can prevent artifact upload despite local pre-update persistence.

For other Java/Python services and static SPA artifacts, follow the [shared adoption guide](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md#extensión-proporcional-a-otros-repositorios). Current [delivery ownership and remaining contracts](docs/deployment-contract.md#local-delivery-implementation) remain explicit.

Local verification on 2026-11-08: frozen Bun installation, Lefthook configuration validation, the installed Git `commit-msg` hook (valid/invalid messages), commitlint stdin/range usage and the actual PR shell step (valid squash/invalid title/blank description) passed. actionlint 1.7.12 checked the workflows without ShellCheck; the 25 focused Python delivery/image/retention tests passed. The 8 Bun release-planning/gate tests and injected recovery failures also passed locally. act 0.2.97 `verify` passed on WSL2/Docker Engine with commitlint, Maven (69 tests, no failures/errors/skips), packaging, image build and packaged-image smoke check. The image-archive checksum/load round-trip also passed. The pre-existing base commit `0e7eed9` produces one nonblocking `footer-leading-blank` warning under the conventional preset; its message/history was preserved. Artifact/cache operations were skipped under act; publication and deployment were not run. GitHub hosted execution/publication and Azure behavior remain unverified. That local verification performed no remote change, registry publication or Azure operation. PR publication for integration is a separate authorized step; it does not enable release or deployment.

Operational references: [GitHub OIDC for Azure](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-azure), [artifact handoff](https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-what-your-workflow-does/storing-and-sharing-data-from-a-workflow), [private registry authentication](https://learn.microsoft.com/en-us/azure/container-apps/containers#container-registries), [ACA image update](https://learn.microsoft.com/en-us/cli/azure/containerapp#az-containerapp-update), [single-revision behavior](https://learn.microsoft.com/en-us/azure/container-apps/revisions#revision-modes) and [GitHub deployment-protection availability](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).

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

### Shared Java automation adoption

[Workspace](https://github.com/pipesor-org/pipesor-workspace/blob/main/README.md)
owns the pinned Maven/report composite and container publication workflow. This
repository retains the CI entry point `ci.yml`, main/PR gates, concurrency, local
JDK 25 setup, contribution/release-tool tests, Dockerfile and image smoke check.
The publisher has no checkout or service-script execution; it consumes the exact
same-run/attempt image archive and preserves candidate schema 1 and naming.
Release/deploy/rollback logic and their source disables are unchanged.

The pinned Workspace commit is integrated in main. Authorize private Actions
access and verify hosted execution before merging this caller migration. No remote publication or access-setting change
is part of this local increment. CI code SHA (including the fixed automation ref)
traces the shared version; upgrade it through reviewed PRs, not floating tags.
For local act verification before private distribution, add
`--local-repository pipesor-org/pipesor-workspace@c11b183f5248b708794192e6d6ce4d781ef340f5=<path-to-reviewed-workspace>`
to the existing command. This substitutes local files and does not validate private
GitHub access or the shared publisher. Do not execute publish/release/deploy locally.

Local validation on 2026-11-11: actionlint passed (ShellCheck omitted), the existing
25 Python delivery/image/retention checks and 8 Bun release checks passed, and
act verify with the local Workspace substitution passed all 69 Java tests plus
packaged-image smoke. Publisher shell/provenance checks use simulated Docker;
private shared-component distribution and the reusable publisher on GitHub remain
unverified. No registry or Azure operation was performed.
