package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.pipesor.pipeline.PostgresIntegrationTest;
import org.pipesor.pipeline.catalog.application.queries.GetRepositoryByIdQuery;
import org.pipesor.pipeline.catalog.application.services.RepositoryQueryService;
import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Transactional
class RepositoryPersistenceTests extends PostgresIntegrationTest {

    private static final String INSERT = """
            INSERT INTO ops.repositories (id, github_repository_id, full_name, default_branch, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RepositoryQueryService queries;

    @Test
    void preservesDistinctRepositoryMetadataAndMicrosecondPrecision() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Instant firstTime = Instant.parse("2026-11-06T12:00:00.123456Z");
        Instant secondTime = Instant.parse("2026-11-05T18:23:47.654321Z");
        insert(firstId, "12345678901234567890", "example/payments", "release/2026", firstTime);
        insert(secondId, "98765432109876543210", "another/inventory", "feature/catalog", secondTime);

        var first = queries.handle(new GetRepositoryByIdQuery(firstId)).orElseThrow();
        var second = queries.handle(new GetRepositoryByIdQuery(secondId)).orElseThrow();

        assertThat(first).extracting(GitHubRepository::getId, GitHubRepository::getGithubRepositoryId,
                GitHubRepository::getFullName, GitHubRepository::getDefaultBranch, GitHubRepository::getCreatedAt)
                .containsExactly(firstId, "12345678901234567890", "example/payments", "release/2026", firstTime);
        assertThat(second).extracting(GitHubRepository::getId, GitHubRepository::getGithubRepositoryId,
                GitHubRepository::getFullName, GitHubRepository::getDefaultBranch, GitHubRepository::getCreatedAt)
                .containsExactly(secondId, "98765432109876543210", "another/inventory", "feature/catalog", secondTime);
        assertThat(queries.handle(new GetRepositoryByIdQuery(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void preservesANullDefaultBranch() {
        UUID id = UUID.randomUUID();
        insert(id, "nullable-branch", "example/no-default", null, Instant.parse("2026-11-06T12:00:00Z"));

        assertThat(queries.handle(new GetRepositoryByIdQuery(id)).orElseThrow().getDefaultBranch()).isNull();
    }

    @Test
    void migrationEnforcesExternalRepositoryUniqueness() {
        Instant createdAt = Instant.parse("2026-11-06T12:00:00Z");
        insert(UUID.randomUUID(), "duplicate", "first/service", "release", createdAt);

        var error = postgresRejection(() -> insert(UUID.randomUUID(), "duplicate",
                "second/service", "feature", createdAt.plusSeconds(1)));

        assertThat(error.getSQLState()).isEqualTo("23505");
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("repositories_github_repository_id_key");
    }

    @Test
    void migrationRejectsDuplicateInternalIdentity() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-11-06T12:00:00Z");
        insert(id, "first-external", "first/service", "release", createdAt);

        var error = postgresRejection(() -> insert(id, "second-external",
                "second/service", "feature", createdAt.plusSeconds(1)));

        assertThat(error.getSQLState()).isEqualTo("23505");
        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("repositories_pkey");
    }

    @Test
    void migrationAllowsEqualNamesForDistinctRepositoryIdentities() {
        Instant createdAt = Instant.parse("2026-11-06T12:00:00Z");
        insert(UUID.randomUUID(), "first-external", "shared/name", "release", createdAt);
        insert(UUID.randomUUID(), "second-external", "shared/name", "feature", createdAt.plusSeconds(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories WHERE full_name = ?",
                Integer.class, "shared/name")).isEqualTo(2);
    }

    @ParameterizedTest(name = "rejects null {0}")
    @CsvSource({"id,0", "github_repository_id,1", "full_name,2", "created_at,4"})
    void migrationRejectsMissingRequiredFields(String column, int position) {
        Object[] values = {UUID.randomUUID(), "required-fields", "example/required", "main",
                Timestamp.from(Instant.parse("2026-11-06T12:00:00Z"))};
        values[position] = null;

        var error = postgresRejection(() -> jdbc.update(INSERT, values));

        assertThat(error.getSQLState()).isEqualTo("23502");
        assertThat(error.getServerErrorMessage().getColumn()).isEqualTo(column);
    }

    @Test
    void migrationUsesNativeUuidAndTimestampTypesAndServiceOwnedHistory() {
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'ops' AND table_name = 'repositories' AND column_name = 'id'
                """, String.class)).isEqualTo("uuid");
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'ops' AND table_name = 'repositories' AND column_name = 'created_at'
                """, String.class)).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ops.pipeline_flyway_schema_history WHERE version = '1' AND success
                """, Integer.class)).isEqualTo(1);
    }

    private void insert(UUID id, String externalId, String fullName, String branch, Instant createdAt) {
        jdbc.update(INSERT, id, externalId, fullName, branch, Timestamp.from(createdAt));
    }

    private PSQLException postgresRejection(Runnable operation) {
        var error = assertThrows(DataIntegrityViolationException.class, operation::run);
        assertThat(error.getMostSpecificCause()).isInstanceOf(PSQLException.class);
        return (PSQLException) error.getMostSpecificCause();
    }
}
