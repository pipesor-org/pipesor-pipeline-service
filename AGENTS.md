# Repository instructions

## Context by task

- Use [README.md](README.md#development-and-verification) for setup, runnable commands and contribution policies. Add verified commands there when introducing the application tooling; reference them rather than maintaining a second command list here.
- For execution/incident behavior, event consumption, API or persistence changes, consult [architecture](docs/architecture.md#pipeline-implementation-context) and the applicable current sources it identifies. Follow links for the affected subject, not the entire documentation set for every edit.
- For dependency selection, runtime configuration, migrations or delivery work, consult the relevant sections of the [deployment contract](docs/deployment-contract.md). Its development baseline records the authorized version-selection policy.
- Treat local architecture summaries as navigation, not frozen specifications. Use the accessible `../../pipesor-docs/` checkout or the linked sources on `main`, accounting for later accepted decisions and relevant uncommitted changes. Inspect uncommitted source changes separately; their presence does not establish integration or approval. Report unavailable or conflicting evidence when it affects the task. Documentation links must follow `main` across repositories and relative paths within this repository; a consulted revision may be recorded as text only.

## Implementation constraints

- Keep pipeline/execution/stage/incident behavior within this service's documented ownership. Check affected callers, events, queries and persistence together when changing behavior; do not add cross-context writes or ORM navigation to make an integration convenient.
- Do not treat inferred access grants as an authorization policy. For consumer changes, verify how duplicate, delayed and independently arriving events affect execution state; use the agreed contract rather than assuming ordering or exactly-once delivery.
- A request to implement a local increment authorizes its required local code, tooling and checks. Do not create cloud resources, permissions or secrets, or modify neighboring repositories as a side effect. Keep credentials and sensitive evidence out of committed files and reported output.
- If a required transition rule, event/API contract or access policy is unresolved, identify the specific pending decision and its impact. Continue independent work; do not invent domain behavior or block the whole service because an unrelated decision remains open.

## Verification and handoff

Use the applicable checks documented in the README. Add behavior checks with the first executable increment, including failure cases material to that change. For documentation-only edits, check affected links and source coherence; parse TOML when changing agent configuration. Do not claim a build or integration passed before it exists and has run.

Keep the documentation that owns a changed behavior or command current in the same change. Report what changed, checks actually performed and remaining blockers. For substantial work spanning sessions, keep a task-specific plan with scope, acceptance criteria and verification progress; a small edit needs no plan file.

When a focused review is warranted and delegation is authorized, `pipeline_reviewer` checks execution consistency, ownership, delivery semantics and access-policy assumptions. Supply the task and affected paths. Its review supplements the main agent's verification and human review.
