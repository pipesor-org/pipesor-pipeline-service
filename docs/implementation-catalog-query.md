# Controlled increment 2: repository lookup and persistence

## Scope and sources

Implement an internal repository-by-UUID query through the catalog application query/service contract, application handler, persistence port and JPA adapter. Map only the existing ops.repositories fields and author the first Flyway migration. This is a read use case, not a repository/workflow registration policy or an externally authorized API.

Source: pipesor-docs main d44b45b8b14ef97e464327ec351e62da70208da6 (ADR-018, DD-001/013, model/domain.yml entities[repositories], and integrated backend/Java conventions). Pipeline documentation main a447cf36e63dd6eadf087af5632970a0394873e6. The unpublished bootstrap commit was rebased onto that integrated Pipeline baseline before this increment. No neighboring repository edits.

Affected surfaces: Maven dependencies; domain entity/base identity; application query/service/handler/port; JPA mapping/base creation metadata/transformer/adapter; Flyway migration and local configuration; existing startup tests now requiring PostgreSQL; integration/unit tests; README and delivery context. No existing production callers exist. The HTTP specification retains no business paths.

## Acceptance criteria and plan

1. Read the documented repository shape without introducing columns or state rules.
2. Keep UUID internal identity and external GitHub text distinct; preserve nullable default_branch and created_at as Instant/timestamptz. Domain remains free of framework dependencies.
3. Query through the application service and JPA adapter; unknown UUID returns Optional.empty. No application write method or registration endpoint.
4. Flyway owns schema evolution; Hibernate validates only, uses standard snake_case physical naming and explicit ops.repositories mapping. Use a service-specific history table. Default startup does not execute migrations; local profile and isolated tests opt in.
5. Verify real PostgreSQL migration, native types, mapping, uniqueness, required timestamps and query results; rerun HTTP documentation checks with the database-enabled application.
6. Update README with actual setup changes, commands and verification evidence.

## Dependency and fixture policy

Use dependency versions managed by pinned Spring Boot 4.1.2: Hibernate 7.4.13.Final, Flyway 12.4.0, PostgreSQL JDBC 42.7.14 and Testcontainers 2.0.5. Verify the resolved combination by build and integration tests. Add no mapper or domain dependency. PostgreSQL test image is pinned to the digest resolved from official postgres:17 on 2026-11-06: sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3.

Spring Boot manages disposable PostgreSQL test containers through @ServiceConnection. All rows and credentials are synthetic; tests do not accept an existing database URL. Missing Docker is a failure, not a skipped integration check. Migration/fixture DDL is limited to this service's ops data and Flyway metadata.

## Verification progress

Completed implementation and Maven verification on 2026-11-06:

- ./mvnw -B -ntp clean verify: BUILD SUCCESS; 13 tests, zero failures/errors/skips. Three query-handler/contract unit tests, six PostgreSQL persistence/constraint tests and four retained HTTP/OpenAPI tests.
- PostgreSQL 17.11 verified from the pinned image. Flyway applied V1 and Hibernate validated the resulting schema. Integration checks cover UUID/text separation, creation-time mapping, nullable branch, unknown UUID, external-ID uniqueness, required creation time and service-specific migration history.
- Domain imports reviewed: no Spring/JPA/Hibernate dependencies. No source-model or neighboring repository changes.
- Standalone packaged JAR with a fresh disposable database: default startup failed validation for the missing ops.repositories table and created no ops tables; local startup applied V1, validated JPA and served OpenAPI/Swagger UI. The application process and container were cleaned up. An auxiliary probe initially expected an outdated Hibernate error spelling; corrected that probe without application changes before obtaining this result.
- git diff --check, POM XML parsing, source whitespace, domain dependency review and changed-document link/anchor checks passed. The 13 Surefire results contain zero skips/failures/errors. No schema or application was deployed outside disposable local checks.

Maven emitted optional upstream Flyway repository-metadata HTTP 401 warnings; dependencies resolved from Maven Central and the build passed. Java 25 emitted native-access and Mockito/Byte Buddy dynamic-agent warnings; no warning suppression or unrelated tooling configuration was added. This is local PostgreSQL verification, not Entra/private-network/cloud validation.

## Limits and remaining decisions

The domain entity and JPA base expose immutable identity/creation metadata for reads. UUID allocation and creation-audit callbacks will be implemented with the first write use case; no setters/save method are exposed here. updated_at is absent because the source model does not define it. No command/async handler, workflow registration, RBAC policy, REST contract, broker consumer, cloud permissions or deployment is invented. PD-007/008/031/033/034/035 remain prerequisites for their affected increments. Local Flyway execution does not approve the cloud migration runner or schema grants.

## Clean Architecture review adjustment

Scope: move query and use-case contract into application; make the handler plain Java and register it in CatalogConfiguration; keep the read-only transaction around the JPA adapter; rename the technical Spring Data interface/entity to SpringDataGitHubRepository and GitHubRepositoryJpaEntity. Update callers, tests and shared/local conventions. No database schema, query result or external contract changes.

Acceptance: existing 13 tests pass with the new bean composition; domain and application compile together using only JDK 25, without a dependency classpath; no references to the old Java types/packages; shared/local conventions agree.

Verification of this adjustment completed on 2026-11-06: ./mvnw -B -ntp clean verify passed again with 13 tests and zero failures/errors/skips. Independently compiled the six domain/application source files with javac --release 25 --class-path empty into a disposable directory; jdeps -s reported only java.base. Shared/local conventions and source imports/names were checked. The earlier packaged-JAR probe remains historical; it was not repeated after this refactor, whose current verification includes real HTTP/application startup through the integration suite. No SQL migration or schema changes were made by the refactor.

## Review handoff

User requested closing this increment in a commit and PR before beginning Incident modeling. The branch feature/pipeline-bootstrap includes the executable bootstrap and the internal lookup/persistence increment with its Clean Architecture refinement. Convention dependency: [pipesor-docs PR #7](https://github.com/pipesor-org/pipesor-docs/pull/7), candidate 752bdcfea69c235775e9560a366d343c2bb2d959. The previously approved Pipeline documentation baseline is a447cf36e63dd6eadf087af5632970a0394873e6.

Current verification uses the latest 13 passing tests and JDK-only core compilation; Java sources are unchanged since that test run. This handoff changes documentation links only and does not claim a fresh packaged-JAR probe, CI or cloud validation. Leave implementation and convention PRs open for the user's approval; no merge or deployment is part of this handoff.
