package org.pipesor.pipeline;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V7 must number incidents that already exist, which an empty test database never exercises. */
class DisplayNumberMigrationTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.POSTGRES_IMAGE);
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    static void stop() { POSTGRES.stop(); }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas("ops").defaultSchema("ops")
                .table("pipeline_flyway_schema_history").target(target).load();
    }

    @Test
    void numbersExistingIncidentsInCreationOrderAndContinuesTheSequence() {
        flyway("6").migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var repository = UUID.randomUUID();
        var pipeline = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, '1', 'o/r', now())", repository);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file, criticality,
                        monitoring_status, created_at) VALUES (?, ?, '2', 'CI', 'ci.yml', 'HIGH', 'ACTIVE', now())""", pipeline, repository);
        // Inserted newest first, so creation order differs from insertion (physical) order.
        var newer = incident(jdbc, pipeline, "2026-11-21T00:00:00Z");
        var older = incident(jdbc, pipeline, "2026-11-20T00:00:00Z");

        flyway("latest").migrate();

        assertThat(jdbc.queryForObject("SELECT display_number FROM ops.incidents WHERE id = ?", Long.class, older)).isEqualTo(1001L);
        assertThat(jdbc.queryForObject("SELECT display_number FROM ops.incidents WHERE id = ?", Long.class, newer)).isEqualTo(1002L);
        assertThat(jdbc.queryForObject("SELECT nextval('ops.incident_display_number_seq')", Long.class)).isEqualTo(1003L);
    }

    /** One incident with its required ORIGIN run, committed together so the deferred origin check passes. */
    private static UUID incident(JdbcTemplate jdbc, UUID pipeline, String createdAt) {
        var incident = UUID.randomUUID();
        var execution = UUID.randomUUID();
        jdbc.execute(String.join("\n", List.of(
                "BEGIN;",
                "INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha, external_status,"
                        + " external_conclusion, failed, started_at, completed_at, correlation_id, created_at) VALUES ('" + execution + "', '"
                        + pipeline + "', '" + execution + "', 1, 'main', 'sha', 'completed', 'failure', true, now(), now(), 'c', now());",
                "INSERT INTO ops.incidents (id, pipeline_id, status, stage, summary, detected_at, correlation_id, created_at) VALUES ('"
                        + incident + "', '" + pipeline + "', 'OPEN', 'TESTING', 'Fixture', now(), 'c', '" + createdAt + "');",
                "INSERT INTO ops.incident_runs (id, incident_id, execution_id, role, correlation_id, created_at) VALUES ('"
                        + UUID.randomUUID() + "', '" + incident + "', '" + execution + "', 'ORIGIN', 'c', now());",
                "COMMIT;")));
        return incident;
    }
}
