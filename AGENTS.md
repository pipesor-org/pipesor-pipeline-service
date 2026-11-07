# Repository instructions

## Context by task

- Before implementation, read the [common backend conventions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/backend-conventions.md) and [Java/Spring conventions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/java-spring-conventions.md). Use the accessible pipesor-docs checkout when available, distinguishing local changes from integrated main. Apply only the conventions relevant to this service; they do not add tables, integrations or permissions.
- Use [README.md](README.md#development-and-verification) for setup, runnable commands and contribution policies. Add verified commands there when introducing the application tooling; reference them rather than maintaining a second command list here.
- For execution/incident behavior, event consumption, API or persistence changes, consult [architecture](docs/architecture.md#pipeline-implementation-context) and the applicable current sources it identifies. Follow links for the affected subject, not the entire documentation set for every edit.
- For dependency selection, runtime configuration, migrations or delivery work, consult the relevant sections of the [deployment contract](docs/deployment-contract.md). Its development baseline records the authorized version-selection policy.
- Treat local architecture summaries as navigation, not frozen specifications. Use the accessible `../../pipesor-docs/` checkout or the linked sources on `main`, accounting for later accepted decisions and relevant uncommitted changes. Inspect uncommitted source changes separately; their presence does not establish integration or approval. Report unavailable or conflicting evidence when it affects the task. Documentation links must follow `main` across repositories and relative paths within this repository; a consulted revision may be recorded as text only.

## Implementation constraints

- Keep pipeline/execution/stage/incident behavior within this service's documented ownership. Check affected callers, events, queries and persistence together when changing behavior; do not add cross-context writes or ORM navigation to make an integration convenient.
- Human operations follow the [ADR-020 local implementation and boundaries](docs/architecture.md#adr-020-local-implementation-and-boundaries): require current verified GitHub access as well as AIOps grants, including child and referenced resources. Keep connector preflight outside SQL write transactions and recheck local grants in the write unit of work. Without an executable connector contract, retain the unavailable adapter; never add a permissive local fallback or route around it with direct GitHub calls/tokens.
- Do not treat inferred access grants as an authorization policy. For consumer changes, verify how duplicate, delayed and independently arriving events affect execution state; use the agreed contract rather than assuming ordering or exactly-once delivery.
- A request to implement a local increment authorizes its required local code, tooling and checks. Do not create cloud resources, permissions or secrets, or modify neighboring repositories as a side effect. Keep credentials and sensitive evidence out of committed files and reported output.
- If a required transition rule, event/API contract or access policy is unresolved, identify the specific pending decision and its impact. Continue independent work; do not invent domain behavior or block the whole service because an unrelated decision remains open.

## Verification and handoff

Use the applicable checks documented in the README. Add behavior checks with the first executable increment, including failure cases material to that change. For documentation-only edits, check affected links and source coherence; parse TOML when changing agent configuration. Do not claim a build or integration passed before it exists and has run.

Keep the documentation that owns a changed behavior or command current in the same change. Report what changed, checks actually performed and remaining blockers. Keep scope and verification notes in the existing README or architecture document when needed; do not create a document per increment. Test meaningful behavior and specific failure causes rather than optimizing test counts. Do not publish branches, PRs or other changes without the user's explicit authorization for publication.

## Task-based agent workflow

This repository authorizes the primary agent to delegate relevant work within the user's existing task authorization. For substantial work with ambiguous requirements, roadmap questions or consequential contract changes, use `service_analyst` for bounded research, the built-in `worker` for implementation and `pipeline_reviewer` for independent review. Use `explorer` for narrow code questions. Small, well-specified fixes can be implemented directly with proportionate review; do not run every role mechanically.

The primary agent remains responsible for scope, architecture consistency, integration, verification and completion. Reuse the analyst's evidence; reread sources when disputed, changed or insufficient. Carry forward applicable user approvals. Routine implementation choices do not require repeated confirmation. Only genuinely unresolved decisions block their dependent work; continue independent authorized work. Analysis/review does not add an approval layer or authorize publication, architecture changes or external writes.

Use this sequence when appropriate: bounded analysis → select specified and authorized work → worker implementation → independent review → fixes → primary-agent verification and report. Parallelize only independent tasks, with at most two open subagent threads excluding the primary. Assign workers explicit file ownership, scope, acceptance criteria and relevant paths; preserve others' changes and do not delegate integration conflicts away. Research/review roles do not edit or delegate.

Pass a compact feature brief, not the full research conversation:

- Goal, scope and exclusions; source links/paths, consulted revisions and relevant local changes.
- Consequential requirements with provenance and status: integrated, explicitly approved locally, proposed, assumed/inferred or unresolved. A local approval does not make the source integrated.
- Observable acceptance criteria and material failures; affected callers, contracts, persistence, configuration and dependencies.
- Open decisions with the exact question, impact, options/recommendation and documented approval authority (or its absence); ordered work ready now versus awaiting a decision/contract.

Workers implement the smallest complete authorized change, meaningful checks and owning documentation; return changed files, exact verification results, deviations and blockers. Escalate conflicting requirements without inventing policy. Reviewers receive the same brief but independently inspect connected paths and source assumptions, assess acceptance-criteria coverage and failure-test effectiveness, and distinguish simulated from real integration. Their findings supplement primary-agent verification and human review.

Keep handoffs in agent messages. Persist durable accepted decisions in existing owning documents; no document per increment. On the first suitable feature, assess repeated research, unnecessary clarification loops and requirement-related rework before adopting this pilot across services. Model/effort defaults and configuration discovery are documented in the [optional Codex setup](README.md#optional-codex-setup); adjust them to demonstrated task complexity rather than using maximum effort by default.
