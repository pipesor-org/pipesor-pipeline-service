package org.pipesor.pipeline;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PIPESOR_REGISTRATION_BROWSER", matches = ".+")
class WorkflowRegistrationBrowserTest {
    private static final String REPOSITORY = "823901";
    private static final String WORKFLOW = "918901";
    private static final String ACTOR = ApiContractTests.PRINCIPAL;
    private static final String REPOSITORY_NAME = "pilot/reporting-service";
    private static final String WORKFLOW_NAME = "Integration checks";
    private static final String WORKFLOW_FILE = ".github/workflows/integration-checks.yml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void registersInChromiumAndReadsAfterRestartOnRetainedPostgres() throws Exception {
        try (var database = new PostgreSQLContainer(PostgresTestConfiguration.POSTGRES_IMAGE)
                .withInitScript("db/shared-audit.sql")) {
            database.start();
            try (var app = start(database)) {
                int port = app.getWebServer().getPort();
                var github = app.getBean(GitHubConnectorDouble.class);
                browser(port, "register");
                var jdbc = app.getBean(JdbcTemplate.class);
                assertRegistrationOnly(jdbc);
                var id = jdbc.queryForObject("SELECT id::text FROM ops.pipelines", String.class);
                assertThat(request(port, "POST", "", payload("999999", "dev")).statusCode()).isIn(403, 404);
                assertThat(request(port, "POST", "", payload(WORKFLOW, "dev").replace("HIGH", "INVALID")).statusCode()).isEqualTo(400);
                assertThat(request(port, "POST", "", payload(WORKFLOW, "dev")).statusCode()).isEqualTo(409);
                github.allow(ACTOR, REPOSITORY, false);
                assertThat(request(port, "POST", "", payload(WORKFLOW, "dev")).statusCode()).isEqualTo(403);
                github.unlink(ACTOR);
                assertThat(request(port, "GET", "/" + id, "").statusCode()).isEqualTo(403);
                github.allow(ACTOR, REPOSITORY, true);
                github.unavailable = true;
                assertThat(request(port, "POST", "", payload(WORKFLOW, "dev")).statusCode()).isEqualTo(503);
                assertThat(request(port, "GET", "/" + id, "").statusCode()).isEqualTo(503);
            }
            try (var restarted = start(database)) {
                browser(restarted.getWebServer().getPort(), "restart");
                assertRegistrationOnly(restarted.getBean(JdbcTemplate.class));
            }
        }
    }

    private static void assertRegistrationOnly(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipelines", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incidents", Integer.class)).isZero();
    }

    private static ServletWebServerApplicationContext start(PostgreSQLContainer database) {
        var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(PipelineServiceApplication.class,
                ApiContractTests.SignedIdentityConfiguration.class, GitHubConnectorTestConfiguration.class)
                .run("--server.port=0", "--server.address=127.0.0.1", "--spring.flyway.enabled=true",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(),
                        "--spring.datasource.password=" + database.getPassword());
        var github = app.getBean(GitHubConnectorDouble.class);
        github.allow(ACTOR, REPOSITORY, true);
        github.workflow(ACTOR, REPOSITORY, WORKFLOW, REPOSITORY_NAME, "main", WORKFLOW_NAME, WORKFLOW_FILE);
        return app;
    }

    private static String payload(String workflow, String environment) throws Exception {
        return JSON.writeValueAsString(Map.of("githubRepositoryId", REPOSITORY, "githubWorkflowId", workflow,
                "criticality", "HIGH", "monitoringStatus", "ACTIVE", "environment", environment));
    }

    private static HttpResponse<Void> request(int port, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/pipelines" + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", ApiContractTests.bearer())
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static void browser(int port, String phase) throws Exception {
        var log = Files.createTempFile("pipesor-registration-browser-", ".log");
        var command = new ProcessBuilder("node",
                Path.of(System.getenv("PIPESOR_REGISTRATION_BROWSER")).toAbsolutePath().toString(), phase)
                .redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("PIPESOR_DEMO_PORT", Integer.toString(port));
        command.environment().put("PIPESOR_REGISTRATION_TOKEN", ApiContractTests.bearer().substring("Bearer ".length()));
        command.environment().put("PIPESOR_DEMO_REGISTRATION_CATALOG", JSON.writeValueAsString(List.of(Map.of(
                "repository", REPOSITORY_NAME, "repositoryId", REPOSITORY, "workflowId", WORKFLOW,
                "workflowName", WORKFLOW_NAME, "workflowPath", WORKFLOW_FILE, "defaultBranch", "main"))));
        var process = command.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            System.out.print(Files.readString(log));
            Files.deleteIfExists(log);
        }
    }
}
