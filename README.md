# pipesor-pipeline-service

Pipeline Service owns monitored workflows, executions, stages, states, metadata and incidents in the `ops` context. Its canonical architecture ID is `pipeline_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

This repository currently contains documentation and optional Codex configuration. Application code, a Maven project, tests, container images and release automation have not been implemented.

## Documentation

- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [First implementation agreement](docs/architecture.md#first-implementation-agreement): package structure, handlers, persistence, identity, audit metadata and OpenAPI conventions.
- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the selected toolchain and dependency policy. There is no application startup, build or test command yet. The first implementation must document its Maven commands here after verifying them.

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
