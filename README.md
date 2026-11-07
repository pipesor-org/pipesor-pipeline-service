# pipesor-pipeline-service

Pipeline Service owns monitored workflows, executions, stages, states, metadata and incidents in the `ops` context. Its canonical architecture ID is `pipeline_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The application targets Java 25 / Spring Boot 4.1.2 with Maven Wrapper 3.10.0 and local OpenAPI/Swagger UI (springdoc 3.2.0). The second controlled increment adds an internal repository-by-UUID query through domain/application/JPA and a Flyway migration for ops.repositories. Public business endpoints, registration commands, broker integration, deployable container images and release automation have not been implemented.

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

Wait for PostgreSQL to report that it accepts connections. In a second terminal, supply that database connection and start the packaged application:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/pipeline_dev \
SPRING_DATASOURCE_USERNAME=pipeline_dev \
SPRING_DATASOURCE_PASSWORD=pipeline_dev \
java -jar target/pipesor-pipeline-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=0
```

The local profile applies migrations to this development database and enables API documentation. Use it only with a disposable development target. Without the local profile, Flyway and documentation are disabled; Hibernate still validates the schema, so startup requires an existing compatible database. No credentials or database target default is embedded in application configuration.

Read the assigned HTTP port in the `Tomcat started on port` log entry. OpenAPI: `http://127.0.0.1:<port>/v3/api-docs`; Swagger UI: `http://127.0.0.1:<port>/swagger-ui/index.html`. The specification still has no business paths. HTTP binds to `127.0.0.1`; these settings are local development choices, not an approved ACA runtime/access contract. The internal query is available through RepositoryQueryService, not HTTP.

Stop the application with Ctrl+C. Stop the example database with `docker stop pipeline-postgres-dev`; its container and data are disposable and removed on exit. The Flyway history table is ops.pipeline_flyway_schema_history; application schema changes come from Flyway, with Hibernate ddl-auto=validate and open-in-view disabled.

Regression checks also cover distinct metadata, PostgreSQL microsecond precision, exact identity/required-field rejection and the actual default/local startup migration contract. The [increment record](docs/implementation-catalog-query.md#verification-progress) preserves historical verification evidence. The Windows wrapper remains untested on Windows.

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

Start Codex from this repository root so its [repository instructions](AGENTS.md) are discovered. Project configuration is in [.codex/config.toml](.codex/config.toml); [pipeline_reviewer](.codex/agents/pipeline-reviewer.toml) defines a focused reviewer. Review roles do not create separate sandbox permissions.

After changing instructions, start a fresh session and ask it to identify its active instruction sources. See the official [instruction-discovery guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md) and [subagent guide](https://learn.chatgpt.com/docs/agent-configuration/subagents). A successful TOML parse alone does not establish live discovery or reviewer execution.
