package org.pipesor.pipeline;

import org.flywaydb.core.Flyway;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupContractTests {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(PostgresTestConfiguration.POSTGRES_IMAGE);
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDisposableDatabase() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @AfterAll
    static void stopDisposableDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetOnlyTheDisposableSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS ops CASCADE");
    }

    @Test
    void defaultStartupRejectsAnEmptySchemaWithoutCreatingTables() {
        assertThatThrownBy(() -> {
            try (var ignored = start("")) {
                // Close the context even if a regression unexpectedly permits startup.
            }
        }).hasRootCauseInstanceOf(SchemaManagementException.class);

        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'ops'
                """, String.class)).isEmpty();
    }

    @Test
    void defaultStartupUsesAnExistingSchemaWithHibernateValidationOnly() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("ops").schemas("ops").table("pipeline_flyway_schema_history")
                .load().migrate();

        try (var context = start("")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }
    }

    @Test
    void localStartupMigratesAnEmptyDatabase() {
        try (var context = start("local")) {
            assertThat(context.isActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories", Integer.class)).isZero();
            assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("1");
        }
    }

    private ConfigurableApplicationContext start(String profile) {
        return new SpringApplicationBuilder(PipelineServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=" + profile,
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }
}
