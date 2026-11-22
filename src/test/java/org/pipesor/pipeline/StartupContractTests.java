package org.pipesor.pipeline;

import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.shared.security.TestIdentity;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Unmodified startup: Flyway migrates ops and creates audit.audit_events if absent; Hibernate only validates. */
class StartupContractTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.POSTGRES_IMAGE);
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDisposableDatabase() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterAll
    static void stopDisposableDatabase() { POSTGRES.stop(); }

    @BeforeEach
    void resetOnlyTheDisposableSchemas() {
        jdbc.execute("DROP SCHEMA IF EXISTS ops CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS audit CASCADE");
    }

    @Test
    void startupMigratesAnEmptyDatabaseIncludingTheAuditTable() {
        try (var context = start("local")) {
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events", Integer.class)).isZero();
            assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("7");
        }
    }

    @Test
    void keepsAnAuditTableProvisionedBeforePipeline() {
        jdbc.execute("CREATE SCHEMA audit");
        jdbc.execute("CREATE TYPE audit.\"AuditCategory\" AS ENUM ('diagnostic_generation', 'evidence_usage', 'human_review', 'access_and_governance_events')");
        jdbc.execute("""
                CREATE TABLE audit.audit_events (id uuid PRIMARY KEY NOT NULL, category audit."AuditCategory" NOT NULL,
                    correlation_id text NOT NULL, actor_ref text, entity_type text NOT NULL, entity_id text NOT NULL,
                    action text NOT NULL, before_state jsonb, after_state jsonb, occurred_at timestamptz NOT NULL,
                    created_at timestamptz NOT NULL)
                """);
        jdbc.update("""
                INSERT INTO audit.audit_events (id, category, correlation_id, entity_type, entity_id, action, occurred_at, created_at)
                VALUES (gen_random_uuid(), 'human_review', 'kept', 'pipeline', 'x', 'kept', now(), now())
                """);
        try (var context = start("local")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events", Integer.class)).isOne();
        }
    }

    private ConfigurableApplicationContext start(String profile) {
        var arguments = new ArrayList<String>();
        arguments.add("--spring.profiles.active=" + profile);
        arguments.add("--server.port=0");
        arguments.add("--pipeline.messaging.enabled=false");
        arguments.add("--pipeline.ingestion.base-uri=http://127.0.0.1:9");
        arguments.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
        arguments.add("--spring.datasource.username=" + POSTGRES.getUsername());
        arguments.add("--spring.datasource.password=" + POSTGRES.getPassword());
        TestIdentity.properties().forEach((name, value) -> arguments.add("--" + name + "=" + value));
        return new SpringApplicationBuilder(PipelineServiceApplication.class).run(arguments.toArray(String[]::new));
    }
}
