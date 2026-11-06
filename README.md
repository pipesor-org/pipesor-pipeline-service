# pipesor-pipeline-service

Pipeline Service owns monitored workflows, executions, stages, states, metadata and incidents in the `ops` context. Its canonical architecture ID is `pipeline_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The first controlled increment provides a Java 25 / Spring Boot 4.1.2 application, Maven Wrapper 3.10.0, local OpenAPI/Swagger UI with springdoc 3.2.0, and HTTP startup tests. Business use cases, persistence, broker integration, container images and release automation have not been implemented.

## Documentation

- [Bootstrap implementation record](docs/implementation-bootstrap.md): scope, dependency selection, acceptance criteria and actual verification results.
- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [First implementation agreement](docs/architecture.md#first-implementation-agreement): package structure, handlers, persistence, identity, audit metadata and OpenAPI conventions.
- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the selected toolchain and dependency policy. Run these commands from this repository root with JDK 25. The first wrapper/build invocation needs HTTPS access to Maven Central and a writable Maven cache; no database, Azure access or credentials are needed.

Compile, run all four HTTP tests and package the executable JAR:

```bash
./mvnw -B -ntp clean verify
```

Start the packaged application with local API documentation on an available loopback port:

```bash
java -jar target/pipesor-pipeline-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=0
```

Read the assigned port in the `Tomcat started on port` log entry. OpenAPI is available at `http://127.0.0.1:<port>/v3/api-docs`, and Swagger UI at `http://127.0.0.1:<port>/swagger-ui/index.html`. Stop the server with Ctrl+C. The generated specification currently has no business paths. The default configuration disables both documentation endpoints and binds to `127.0.0.1`; the local profile enables documentation. These are local development settings, not an approved ACA runtime or access policy.

Verified on 2026-11-06 with Java 25.0.4.1: wrapper build, four passing HTTP tests, executable-JAR startup, OpenAPI JSON, Swagger UI and process termination. Maven's generated Windows wrapper is included but has not been executed on Windows. No application process is left running after verification.

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
