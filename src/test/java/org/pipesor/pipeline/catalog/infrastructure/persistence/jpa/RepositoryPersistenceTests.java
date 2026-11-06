package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.PostgresIntegrationTest;
import org.pipesor.pipeline.catalog.application.queries.GetRepositoryByIdQuery;
import org.pipesor.pipeline.catalog.application.services.RepositoryQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class RepositoryPersistenceTests extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RepositoryQueryService queries;

    @Test
    void readsUuidTextAndCreationTimeThroughTheHandlerAndJpa() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-11-06T12:00:00Z");
        insert(id, "12345678901234567890", "main", createdAt);

        var repository = queries.handle(new GetRepositoryByIdQuery(id)).orElseThrow();

        assertThat(repository.getId()).isEqualTo(id);
        assertThat(repository.getGithubRepositoryId()).isEqualTo("12345678901234567890");
        assertThat(repository.getFullName()).isEqualTo("example/service");
        assertThat(repository.getDefaultBranch()).isEqualTo("main");
        assertThat(repository.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void preservesANullDefaultBranch() {
        UUID id = UUID.randomUUID();
        insert(id, "nullable-branch", null, Instant.parse("2026-11-06T12:00:00Z"));

        assertThat(queries.handle(new GetRepositoryByIdQuery(id)).orElseThrow().getDefaultBranch()).isNull();
    }

    @Test
    void returnsEmptyForAnUnknownUuid() {
        assertThat(queries.handle(new GetRepositoryByIdQuery(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void migrationEnforcesExternalRepositoryUniqueness() {
        Instant createdAt = Instant.parse("2026-11-06T12:00:00Z");
        insert(UUID.randomUUID(), "duplicate", "main", createdAt);

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "duplicate", "main", createdAt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationRequiresCreationTime() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ops.repositories (id, github_repository_id, full_name)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), "missing-creation-time", "example/service"))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private void insert(UUID id, String externalId, String branch, Instant createdAt) {
        jdbc.update("""
                INSERT INTO ops.repositories (id, github_repository_id, full_name, default_branch, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, externalId, "example/service", branch, Timestamp.from(createdAt));
    }
}
