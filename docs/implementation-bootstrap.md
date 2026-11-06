# Controlled increment 1: application bootstrap

## Scope and sources

Session-authorized on 2026-11-06. Implement the first build/startup/test increment of the [implementation agreement](architecture.md#first-implementation-agreement). Consulted pipesor-docs c84ef3e4629fcb0c38315f32b641a5fb949e450d plus the separately inspected, uncommitted backend conventions authorized in this session. Those conventions are not claimed as merged.

Affected surfaces: Maven manifest/wrapper, application entry point, runtime configuration, HTTP startup/OpenAPI tests, ignore rules and current README/delivery documentation. No existing application callers or fixtures exist. No business API, schema, data, credentials, broker consumer or neighboring repository changes are required.

## Acceptance criteria

- Compile for the installed Java 25 baseline with pinned stable Spring Boot/springdoc versions and Maven Wrapper.
- Verify real HTTP startup on a random loopback port, without external dependencies.
- Verify documentation is disabled by default, and OpenAPI/Swagger UI work under the local profile. The generated specification has no business paths yet.
- Build an executable JAR and verify its local startup and HTTP response.
- Document commands actually run and preserve pending functional/integration/access contracts.

## Dependency selection

Maven Central metadata checked on 2026-11-06: Spring Boot 4.1.2 and springdoc 3.2.0 are the latest stable releases; Boot 4.2.0-RC2 is a prerelease and excluded. springdoc 3.2.0's published parent is Boot 4.1.2. Boot's requirements include Java 25. Maven Wrapper plugin 3.3.4 generated the official only-script wrapper pinned to the installed Maven 3.10.0.

Sources: [Boot requirements](https://docs.spring.io/spring-boot/4.1/system-requirements.html), [Boot metadata](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml), [springdoc published POM](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi/3.2.0/springdoc-openapi-3.2.0.pom). Transitive dependencies use the Boot parent management; direct springdoc is pinned explicitly.

## Verification progress

Completed on 2026-11-06 with Java 25.0.4.1:

- Official wrapper generated successfully with plugin 3.3.4 and Maven 3.10.0.
- `./mvnw -B -ntp clean verify`: BUILD SUCCESS; four tests, zero failures/errors/skips. Tests start real HTTP servers on random ports, covering documentation disabled by default and local OpenAPI plus Swagger UI.
- Packaged JAR launched with the README command; an isolated HTTP check parsed the OpenAPI JSON (3.x, empty paths) and checked Swagger UI returns HTML/200. The process terminated normally after the check, with no server left running.
- Documentation, source links and whitespace are checked at final handoff. No persistent model or generated architecture artifacts changed.

The test runtime emitted Mockito/Byte Buddy dynamic-agent warnings on Java 25; tests passed. No mock setup or warning-suppression configuration was added for this bootstrap. The generated Windows wrapper has not been executed on Windows. No container/CI, persistence, broker, load or Azure verification is claimed.

## Remaining scope

JPA, Flyway, naming, UUID/audit bases and handlers will be introduced with their first real persistence/use case. updated_at needs the documented source-model evolution first. PD-003/007/008/031/033/034/035 remain applicable; this local bootstrap does not close contracts or establish deployability. No Azure resources, image, release automation or shared-library scaffolding are created.

## Branch and review dependency

Implementation is isolated on the short-lived local branch feature/pipeline-bootstrap, based on documentation candidate 978151358d79407df46823b6f5d14e5c2041fc23. The documentation remains pending in [Pipeline PR #3](https://github.com/pipesor-org/pipesor-pipeline-service/pull/3), which depends on [shared conventions PR #6](https://github.com/pipesor-org/pipesor-docs/pull/6), candidate 5f1e3c16cbde3402ffec73eab412b1d2368ae4be. These commits are not approved or merged baselines. This dependent local branch avoids mixing executable code into the documentation review; after those approvals/merges, reconcile it with main before opening the implementation PR. No implementation push or deployment accompanies this separation.

## Subsequent integration status

Before increment 2, documentation PRs #3 and #6 were confirmed merged. Pipeline main is a447cf36e63dd6eadf087af5632970a0394873e6; shared conventions main is d44b45b8b14ef97e464327ec351e62da70208da6. The unpublished bootstrap commit was rebased onto Pipeline main (now a91bf34). Earlier pending-PR statements above describe the historical first-increment handoff. Current setup and database requirements are documented by the [repository lookup increment](implementation-catalog-query.md) and README.
