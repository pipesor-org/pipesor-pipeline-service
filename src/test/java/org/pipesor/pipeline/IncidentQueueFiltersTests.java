package org.pipesor.pipeline;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.security.TestIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-027: queue filters, exact totals, filter-bound page tokens, enriched items, the diagnosis summary projection,
 * the summary counts and Insights, always after the ADR-020 authorization (grant plus current GitHub read).
 */
@Import(GitHubConnectorTestConfiguration.class)
class IncidentQueueFiltersTests extends PostgresIntegrationTest {
    private static final String ACTOR = ApiContractTests.PRINCIPAL;
    private static final String SUMMARY_ROLE = "Recommendation.DiagnosisSummaries.Write";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");
    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubConnectorDouble github;
    @Autowired IncidentCommandService incidents;
    @Autowired PlatformTransactionManager manager;
    private final List<UUID> repositories = new ArrayList<>();
    private final List<UUID> pipelines = new ArrayList<>();
    private final Map<UUID, UUID> origins = new LinkedHashMap<>();
    private UUID payments;
    private UUID web;
    private UUID denied;
    private UUID ungranted;
    private int order;

    @BeforeEach
    void seed() {
        github.reset();
        payments = pipeline("830001", "acme/payments-api", "931001", "Deploy prod", true);
        web = pipeline("830002", "acme/web_app", "931002", "CI", true);
        denied = pipeline("830003", "acme/secret-payments", "931003", "Deploy prod", true);
        ungranted = pipeline("830004", "acme/payments-ungranted", "931004", "Deploy prod", false);
        github.facts(ACTOR, "830001", new GitHubConnector.RepositoryAccess("830001", "5001", "7001", true, true, false));
        github.facts(ACTOR, "830002", new GitHubConnector.RepositoryAccess("830002", "5001", "7001", true, true, false));
        github.facts(ACTOR, "830003", new GitHubConnector.RepositoryAccess("830003", "5001", "7001", true, false, false));
        github.facts(ACTOR, "830004", new GitHubConnector.RepositoryAccess("830004", "5001", "7001", true, true, false));
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            for (UUID id : pipelines) {
                jdbc.update("DELETE FROM ops.incident_diagnoses WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id = ?)", id);
                jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id = ?)", id);
                jdbc.update("DELETE FROM ops.incidents WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipeline_executions WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", id);
            }
            for (UUID id : repositories) jdbc.update("DELETE FROM ops.repositories WHERE id = ?", id);
        });
    }

    @Test
    void withoutFiltersTheQueueIsUnchangedAndGainsTotalFiltersAndEnrichedItems() {
        var first = incident(payments, "OPEN", "HIGH", PipelineStage.TESTING);
        var second = incident(web, "IN_REMEDIATION", null, PipelineStage.BUILD);
        incident(denied, "OPEN", "HIGH", PipelineStage.TESTING);
        incident(ungranted, "OPEN", "HIGH", PipelineStage.TESTING);

        var body = get("/api/v1/incidents?state=OPEN");
        assertThat(ids(body)).containsExactly(second, first);
        assertThat(body.get("total").asLong()).isEqualTo(2);
        assertThat(body.get("nextPageToken").isNull()).isTrue();
        var applied = body.get("appliedFilters");
        assertThat(applied.get("state").asString()).isEqualTo("OPEN");
        for (var name : List.of("pipelineId", "repositoryId", "severity", "stage", "status", "diagnosis")) {
            assertThat(applied.get(name).isArray()).isTrue();
            assertThat(applied.get(name)).isEmpty();
        }
        assertThat(applied.get("q").isNull()).isTrue();
        assertThat(applied.get("qMatch").isNull()).isTrue();
        var item = body.get("items").get(1);
        assertThat(item.get("repository").get("repositoryId").asString()).isEqualTo("830001");
        assertThat(item.get("repository").get("fullName").asString()).isEqualTo("acme/payments-api");
        assertThat(item.get("workflow").get("workflowId").asString()).isEqualTo("931001");
        assertThat(item.get("workflow").get("name").asString()).isEqualTo("Deploy prod");
        assertThat(item.get("diagnosis").isNull()).isTrue();
        assertThat(item.get("severity").asString()).isEqualTo("HIGH");
        assertThat(body.get("items").get(0).get("severity").isNull()).isTrue();
        // The single-incident and per-pipeline reads carry the same enrichment.
        var single = get("/api/v1/incidents/" + first);
        assertThat(single.get("repository").get("fullName").asString()).isEqualTo("acme/payments-api");
        assertThat(single.get("diagnosis").isNull()).isTrue();
        var perPipeline = get("/api/v1/pipelines/" + payments + "/incidents");
        assertThat(perPipeline.get("items").get(0).get("workflow").get("name").asString()).isEqualTo("Deploy prod");
    }

    @Test
    void filtersAreAndedAcrossAndOredWithinAndNeverWidenTheAuthorization() {
        var critical = incident(payments, "OPEN", "CRITICAL", PipelineStage.DEPLOYMENT);
        var high = incident(payments, "VERIFICATION", "HIGH", PipelineStage.TESTING);
        var unclassified = incident(web, "OPEN", null, PipelineStage.TESTING);
        var low = incident(web, "IN_REMEDIATION", "LOW", PipelineStage.BUILD);
        var resolved = incident(web, "RESOLVED", "CRITICAL", PipelineStage.TESTING);
        var hidden = incident(denied, "OPEN", "CRITICAL", PipelineStage.DEPLOYMENT);
        var ungrantedIncident = incident(ungranted, "OPEN", "CRITICAL", PipelineStage.DEPLOYMENT);

        assertThat(ids(get("/api/v1/incidents?state=OPEN&severity=CRITICAL"))).containsExactly(critical);
        // OR within one filter, including incidents without severity.
        assertThat(ids(get("/api/v1/incidents?state=OPEN&severity=CRITICAL&severity=UNCLASSIFIED")))
                .containsExactly(unclassified, critical);
        // AND across filters.
        assertThat(ids(get("/api/v1/incidents?state=OPEN&stage=TESTING&repositoryId=830002"))).containsExactly(unclassified);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&stage=TESTING&stage=BUILD&severity=LOW"))).containsExactly(low);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&status=IN_REMEDIATION&status=VERIFICATION")))
                .containsExactly(low, high);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&pipelineId=" + payments))).containsExactly(high, critical);
        assertThat(ids(get("/api/v1/incidents?state=RESOLVED&severity=CRITICAL"))).containsExactly(resolved);
        // Filtering by a repository or pipeline the actor cannot see returns and counts nothing.
        var hiddenByRepository = get("/api/v1/incidents?state=OPEN&repositoryId=830003&repositoryId=830004");
        assertThat(ids(hiddenByRepository)).isEmpty();
        assertThat(hiddenByRepository.get("total").asLong()).isZero();
        var hiddenByPipeline = get("/api/v1/incidents?state=OPEN&pipelineId=" + denied + "&pipelineId=" + ungranted);
        assertThat(ids(hiddenByPipeline)).isEmpty();
        assertThat(hiddenByPipeline.get("total").asLong()).isZero();
        var all = ids(get("/api/v1/incidents?state=OPEN&severity=CRITICAL&severity=HIGH&severity=MEDIUM&severity=LOW&severity=UNCLASSIFIED"));
        assertThat(all).doesNotContain(hidden, ungrantedIncident).hasSize(4);
        // The echo is normalized: deduplicated and in contract order.
        var applied = get("/api/v1/incidents?state=OPEN&severity=LOW&severity=CRITICAL&severity=LOW&repositoryId=830002&repositoryId=830001")
                .get("appliedFilters");
        assertThat(texts(applied.get("severity"))).containsExactly("CRITICAL", "LOW");
        assertThat(texts(applied.get("repositoryId"))).containsExactly("830001", "830002");
    }

    @Test
    void textQueryMatchesDisplayNumbersOrRepositoryAndWorkflowNames() {
        var deploy = incident(payments, "OPEN", "HIGH", PipelineStage.DEPLOYMENT);
        var ci = incident(web, "OPEN", "HIGH", PipelineStage.BUILD);
        incident(denied, "OPEN", "HIGH", PipelineStage.DEPLOYMENT);
        long number = jdbc.queryForObject("SELECT display_number FROM ops.incidents WHERE id = ?", Long.class, deploy);

        var byNumber = get("/api/v1/incidents?state=OPEN&q=INC-" + number);
        assertThat(ids(byNumber)).containsExactly(deploy);
        assertThat(byNumber.get("appliedFilters").get("qMatch").asString()).isEqualTo("DISPLAY_NUMBER");
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=inc-" + number))).containsExactly(deploy);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=" + number))).containsExactly(deploy);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=9"))).isEmpty();
        // Case-insensitive partial match on the repository full name or the workflow name.
        var byText = get("/api/v1/incidents?state=OPEN&q={q}", " PAYMENTS ");
        assertThat(ids(byText)).containsExactly(deploy);
        assertThat(byText.get("appliedFilters").get("q").asString()).isEqualTo("PAYMENTS");
        assertThat(byText.get("appliedFilters").get("qMatch").asString()).isEqualTo("TEXT");
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=ci"))).containsExactly(ci);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q={q}", "deploy PROD"))).containsExactly(deploy);
        // Words are AND-ed and each may match either field; the query and the fields are NFKC-normalized.
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q={q}", "payments   prod"))).containsExactly(deploy);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q={q}", "acme ci"))).containsExactly(ci);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q={q}", "payments ci"))).isEmpty();
        var normalized = get("/api/v1/incidents?state=OPEN&q={q}", " \uFF30\uFF21\uFF39\u3000 deploy ");
        assertThat(ids(normalized)).containsExactly(deploy);
        assertThat(normalized.get("appliedFilters").get("q").asString()).isEqualTo("PAY deploy");
        // LIKE wildcards are literal text.
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=web_app"))).containsExactly(ci);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q={q}", "web%app"))).isEmpty();
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=b_a"))).containsExactly(ci);
        assertThat(ids(get("/api/v1/incidents?state=OPEN&q=b-a"))).isEmpty();
    }

    @Test
    void pageTokensAreBoundToTheFiltersAndTotalIsStableAcrossPages() {
        var expected = new ArrayList<UUID>();
        for (int index = 0; index < 5; index++) expected.addFirst(incident(payments, "OPEN", "HIGH", PipelineStage.TESTING));
        incident(payments, "OPEN", "LOW", PipelineStage.TESTING);
        incident(denied, "OPEN", "HIGH", PipelineStage.TESTING);

        String filters = "state=OPEN&severity=HIGH&q=payments";
        var seen = new ArrayList<UUID>();
        String token = null;
        do {
            var page = get("/api/v1/incidents?" + filters + "&pageSize=2" + (token == null ? "" : "&pageToken=" + token));
            assertThat(page.get("total").asLong()).isEqualTo(5);
            seen.addAll(ids(page));
            token = page.get("nextPageToken").isNull() ? null : page.get("nextPageToken").asString();
        } while (token != null);
        assertThat(seen).containsExactlyElementsOf(expected);

        token = get("/api/v1/incidents?" + filters + "&pageSize=2").get("nextPageToken").asString();
        // Equal criteria in another order are the same listing.
        assertThat(ids(get("/api/v1/incidents?q=PAYMENTS&severity=HIGH&state=OPEN&pageSize=2&pageToken=" + token))).hasSize(2);
        for (var other : List.of("state=OPEN&severity=LOW&q=payments", "state=OPEN&severity=HIGH", "state=RESOLVED&severity=HIGH&q=payments",
                "state=OPEN&severity=HIGH&q=payments&stage=TESTING")) {
            error("/api/v1/incidents?" + other + "&pageSize=2&pageToken=" + token, 400, "INVALID_PAGE_TOKEN");
        }
        error("/api/v1/incidents?state=OPEN&pageToken=bogus", 400, "INVALID_PAGE_TOKEN");
        // Unfiltered tokens of the previous (unbound) format keep working for an unfiltered queue only.
        var last = jdbc.queryForMap("SELECT created_at, id FROM ops.incidents WHERE id = ?", expected.getFirst());
        String legacy = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("q1|"
                + ((Timestamp) last.get("created_at")).toInstant() + "|" + last.get("id")).getBytes());
        assertThat(get("/api/v1/incidents?state=OPEN&pageToken=" + legacy).get("items")).isNotEmpty();
        error("/api/v1/incidents?state=OPEN&severity=HIGH&pageToken=" + legacy, 400, "INVALID_PAGE_TOKEN");
    }

    @Test
    void invalidFilterValuesAreRejected() {
        for (var query : List.of("severity=URGENT", "severity=high", "severity=", "stage=LINT", "status=RESOLVED",
                "diagnosis=UNKNOWN", "pipelineId=not-a-uuid", "repositoryId=abc", "repositoryId=0123", "q=a",
                "q=ab&q=cd", "q=" + "x".repeat(101))) {
            error("/api/v1/incidents?state=OPEN&" + query, 400, "INVALID_FILTER");
            error("/api/v1/incidents/summary?state=OPEN&" + query, 400, "INVALID_FILTER");
        }
        error("/api/v1/incidents?state=OPEN&q={q}", 400, "INVALID_FILTER", "   ");
        error("/api/v1/incidents?state=OPEN&q={q}", 400, "INVALID_FILTER", "a\u0007b");
        error("/api/v1/incidents?state=RESOLVED&status=OPEN", 400, "INVALID_FILTER");
        var tooMany = new StringBuilder("/api/v1/incidents?state=OPEN");
        for (int index = 0; index < 101; index++) tooMany.append("&repositoryId=").append(840000 + index);
        error(tooMany.toString(), 400, "INVALID_FILTER");
        error("/api/v1/incidents?state=CLOSED", 400, "INVALID_REQUEST");
        error("/api/v1/incidents?state=OPEN&pageSize=0", 400, "INVALID_REQUEST");
        // Unknown parameters are ignored, which is why the echo exists.
        assertThat(get("/api/v1/incidents?state=OPEN&severities=HIGH").get("appliedFilters").get("severity")).isEmpty();
    }

    @Test
    void diagnosisSummariesFeedTheDiagnosisFilterMonotonically() {
        var pending = incident(payments, "RESOLVED", "HIGH", PipelineStage.TESTING);
        var confirmed = incident(payments, "RESOLVED", "HIGH", PipelineStage.TESTING);
        var absent = incident(web, "RESOLVED", "HIGH", PipelineStage.BUILD);
        var hidden = incident(denied, "RESOLVED", "HIGH", PipelineStage.TESTING);
        var diagnosis = UUID.randomUUID();
        put(pending, summary(pending, payments, "830001", UUID.randomUUID(), 1, "PENDING", 0, "Flaky integration test"))
                .expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*").expectBody()
                .jsonPath("$.applied").isEqualTo(true).jsonPath("$.status").isEqualTo("PENDING");
        put(confirmed, summary(confirmed, payments, "830001", diagnosis, 1, "PENDING", 0, "Expired credentials"))
                .expectStatus().isOk();
        put(confirmed, summary(confirmed, payments, "830001", diagnosis, 1, "CONFIRMED", 1, "Expired credentials"))
                .expectStatus().isOk().expectBody().jsonPath("$.applied").isEqualTo(true);
        // A replay or a late, older delivery never moves the projection backwards.
        put(confirmed, summary(confirmed, payments, "830001", diagnosis, 1, "PENDING", 0, "Expired credentials"))
                .expectStatus().isOk().expectBody().jsonPath("$.applied").isEqualTo(false);
        put(confirmed, summary(confirmed, payments, "830001", diagnosis, 1, "CONFIRMED", 1, "Expired credentials"))
                .expectStatus().isOk().expectBody().jsonPath("$.applied").isEqualTo(false);
        put(hidden, summary(hidden, denied, "830003", UUID.randomUUID(), 1, "PENDING", 0, null)).expectStatus().isOk();

        var needsValidation = get("/api/v1/incidents?state=RESOLVED&diagnosis=PENDING");
        assertThat(ids(needsValidation)).containsExactly(pending);
        assertThat(needsValidation.get("total").asLong()).isEqualTo(1);
        var item = needsValidation.get("items").get(0).get("diagnosis");
        assertThat(item.get("status").asString()).isEqualTo("PENDING");
        assertThat(item.get("probableCause").asString()).isEqualTo("Flaky integration test");
        assertThat(item.get("createdAt").asString()).isEqualTo("2026-11-02T00:00:00Z");
        assertThat(ids(get("/api/v1/incidents?state=RESOLVED&diagnosis=CONFIRMED"))).containsExactly(confirmed);
        assertThat(ids(get("/api/v1/incidents?state=RESOLVED&diagnosis=ABSENT"))).containsExactly(absent);
        assertThat(ids(get("/api/v1/incidents?state=RESOLVED&diagnosis=ABSENT&diagnosis=PENDING"))).containsExactly(absent, pending);
        assertThat(ids(get("/api/v1/incidents?state=RESOLVED&diagnosis=CORRECTED&diagnosis=REJECTED"))).isEmpty();
        // A newer version replaces the previous one.
        put(confirmed, summary(confirmed, payments, "830001", UUID.randomUUID(), 2, "PENDING", 0, "Second opinion"))
                .expectStatus().isOk().expectBody().jsonPath("$.applied").isEqualTo(true);
        assertThat(ids(get("/api/v1/incidents?state=RESOLVED&diagnosis=PENDING"))).containsExactly(confirmed, pending);
    }

    @Test
    void diagnosisSummaryRouteValidatesCallerBindingAndBody() {
        var incident = incident(payments, "RESOLVED", "HIGH", PipelineStage.TESTING);
        var body = summary(incident, payments, "830001", UUID.randomUUID(), 1, "PENDING", 0, null);
        put(incident, body, null).expectStatus().isUnauthorized();
        put(incident, body, TestIdentity.workload("recommendation", "Recommendation.DiagnosisEntitlements.Resolve")).expectStatus().isForbidden();
        put(incident, body, TestIdentity.workload("correlation", "Correlation.OperationalReferences.Check")).expectStatus().isForbidden();
        put(incident, body, ApiContractTests.bearer().substring("Bearer ".length())).expectStatus().isForbidden();
        put(UUID.randomUUID(), body).expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("INCIDENT_NOT_FOUND");
        // Execution, pipeline and repository must be the incident's.
        var wrongExecution = new LinkedHashMap<>(body);
        wrongExecution.put("executionId", UUID.randomUUID().toString());
        put(incident, wrongExecution).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("SCOPE_MISMATCH");
        put(incident, summary(incident, web, "830001", UUID.randomUUID(), 1, "PENDING", 0, null)).expectStatus().isEqualTo(409);
        put(incident, summary(incident, payments, "830002", UUID.randomUUID(), 1, "PENDING", 0, null)).expectStatus().isEqualTo(409);
        // Inconsistent or malformed bodies.
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 1, "CONFIRMED", 0, null)).expectStatus().isBadRequest();
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 1, "PENDING", 1, null)).expectStatus().isBadRequest();
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 0, "PENDING", 0, null)).expectStatus().isBadRequest();
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 1, "UNKNOWN", 0, null)).expectStatus().isBadRequest();
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 1, "PENDING", 0, "x".repeat(1001))).expectStatus().isBadRequest();
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 1, "PENDING", 0, " ")).expectStatus().isBadRequest();
        var unknown = new LinkedHashMap<>(body);
        unknown.put("actor", "x");
        put(incident, unknown).expectStatus().isBadRequest();
        var missing = new LinkedHashMap<>(body);
        missing.remove("createdAt");
        put(incident, missing).expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incident_diagnoses WHERE incident_id = ?", Long.class, incident)).isZero();
        put(incident, summary(incident, payments, "830001", UUID.randomUUID(), 1, "PENDING", 0, "y".repeat(1000))).expectStatus().isOk();
    }

    @Test
    void summaryCountsTheSameFilteredVisibleSet() {
        incident(payments, "OPEN", "CRITICAL", PipelineStage.DEPLOYMENT);
        incident(payments, "VERIFICATION", "HIGH", PipelineStage.TESTING);
        incident(web, "OPEN", null, PipelineStage.TESTING);
        var reviewed = incident(web, "IN_REMEDIATION", "HIGH", PipelineStage.BUILD);
        incident(web, "RESOLVED", "CRITICAL", PipelineStage.TESTING);
        incident(denied, "OPEN", "CRITICAL", PipelineStage.DEPLOYMENT);
        incident(ungranted, "OPEN", "CRITICAL", PipelineStage.DEPLOYMENT);
        put(reviewed, summary(reviewed, web, "830002", UUID.randomUUID(), 1, "REJECTED", 2, null)).expectStatus().isOk();

        var open = get("/api/v1/incidents/summary?state=OPEN");
        assertThat(open.get("total").asLong()).isEqualTo(4);
        assertThat(counts(open.get("bySeverity"))).containsExactlyInAnyOrderEntriesOf(Map.of("CRITICAL", 1L, "HIGH", 2L, "MEDIUM", 0L,
                "LOW", 0L, "UNCLASSIFIED", 1L));
        assertThat(counts(open.get("byStatus"))).containsExactlyInAnyOrderEntriesOf(Map.of("OPEN", 2L, "IN_REMEDIATION", 1L, "VERIFICATION", 1L));
        assertThat(counts(open.get("byDiagnosis"))).containsExactlyInAnyOrderEntriesOf(Map.of("PENDING", 0L, "CONFIRMED", 0L,
                "CORRECTED", 0L, "REJECTED", 1L, "ABSENT", 3L));
        assertThat(open.get("appliedFilters").get("state").asString()).isEqualTo("OPEN");
        // Every filter applies to every map.
        var filtered = get("/api/v1/incidents/summary?state=OPEN&severity=HIGH&repositoryId=830002");
        assertThat(filtered.get("total").asLong()).isEqualTo(1);
        assertThat(counts(filtered.get("byStatus"))).containsEntry("IN_REMEDIATION", 1L).containsEntry("OPEN", 0L);
        assertThat(get("/api/v1/incidents?state=OPEN&severity=HIGH&repositoryId=830002").get("total").asLong()).isEqualTo(1);
        var resolved = get("/api/v1/incidents/summary?state=RESOLVED");
        assertThat(counts(resolved.get("byStatus"))).containsExactlyEntriesOf(Map.of("RESOLVED", 1L));
        assertThat(get("/api/v1/incidents/summary?state=OPEN&repositoryId=830003").get("total").asLong()).isZero();
        client.get().uri("/api/v1/incidents/summary?state=OPEN").exchange().expectStatus().isUnauthorized();
        github.unavailable = true;
        client.get().uri("/api/v1/incidents/summary?state=OPEN").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void insightsUseTheDashboardDefinitionsOverVisibleResolvedIncidents() {
        // Window [Nov 1, Nov 15): two 7-day buckets.
        var confirmed = resolved(payments, PipelineStage.TESTING, "2026-11-02T00:00:00Z", "2026-11-02T02:00:00Z");
        var corrected = resolved(payments, PipelineStage.TESTING, "2026-11-03T00:00:00Z", "2026-11-03T04:00:00Z");
        var pending = resolved(web, PipelineStage.BUILD, "2026-11-09T00:00:00Z", "2026-11-09T01:00:00Z");
        resolved(web, PipelineStage.BUILD, "2026-11-10T00:00:00Z", "2026-11-10T03:00:00Z");
        resolved(web, PipelineStage.BUILD, "2026-10-20T00:00:00Z", "2026-10-20T01:00:00Z");
        resolved(denied, PipelineStage.TESTING, "2026-11-02T00:00:00Z", "2026-11-02T01:00:00Z");
        resolved(ungranted, PipelineStage.TESTING, "2026-11-02T00:00:00Z", "2026-11-02T01:00:00Z");
        incident(payments, "OPEN", "HIGH", PipelineStage.TESTING);
        diagnose(confirmed, payments, "830001", "CONFIRMED", 1, "2026-11-02T00:10:00Z");
        diagnose(corrected, payments, "830001", "CORRECTED", 2, "2026-11-03T00:20:00Z");
        diagnose(pending, web, "830002", "PENDING", 0, "2026-11-09T00:30:00Z");

        var body = get("/api/v1/insights?from=2026-11-01T00:00:00Z&to=2026-11-15T00:00:00Z&top=1");
        assertThat(body.get("window").get("from").asString()).isEqualTo("2026-11-01T00:00:00Z");
        var summary = body.get("summary");
        assertThat(summary.get("resolvedIncidents").asLong()).isEqualTo(4);
        assertThat(summary.get("confirmed").asLong()).isEqualTo(1);
        assertThat(summary.get("rejected").asLong()).isEqualTo(1);
        assertThat(summary.get("pending").asLong()).isEqualTo(1);
        assertThat(summary.get("confirmationRate").asDouble()).isEqualTo(0.5);
        assertThat(summary.get("meanDiagnosis").get("meanMs").asLong()).isEqualTo(20 * 60_000L);
        assertThat(summary.get("meanDiagnosis").get("sampleSize").asLong()).isEqualTo(3);
        assertThat(summary.get("meanResolution").get("meanMs").asLong()).isEqualTo((2 + 4 + 1 + 3) * 3_600_000L / 4);
        assertThat(counts(body.get("outcomes"))).containsExactlyInAnyOrderEntriesOf(Map.of("PENDING", 1L, "CONFIRMED", 1L,
                "CORRECTED", 1L, "REJECTED", 0L, "ABSENT", 1L));
        // Ties keep the stage order.
        assertThat(body.get("stages").get(0).get("stage").asString()).isEqualTo("BUILD");
        assertThat(body.get("stages").get(1).get("stage").asString()).isEqualTo("TESTING");
        assertThat(body.get("recurring")).hasSize(1);
        var recurring = body.get("recurring").get(0);
        assertThat(recurring.get("count").asLong()).isEqualTo(2);
        assertThat(recurring.get("repository").get("fullName").asString()).isEqualTo("acme/payments-api");
        assertThat(recurring.get("pipelineId").asString()).isEqualTo(payments.toString());
        var trend = body.get("trend");
        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).get("incidentCount").asLong()).isEqualTo(2);
        assertThat(trend.get(0).get("meanDiagnosisMs").asLong()).isEqualTo(15 * 60_000L);
        assertThat(trend.get(1).get("incidentCount").asLong()).isEqualTo(2);
        assertThat(trend.get(1).get("end").asString()).isEqualTo("2026-11-15T00:00:00Z");

        var empty = get("/api/v1/insights?from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z");
        assertThat(empty.get("summary").get("resolvedIncidents").asLong()).isZero();
        assertThat(empty.get("summary").get("confirmationRate").isNull()).isTrue();
        assertThat(empty.get("summary").get("meanDiagnosis").get("meanMs").isNull()).isTrue();
        assertThat(empty.get("trend")).hasSize(1);
        // Defaults: the last 30 calendar days up to now.
        var defaults = get("/api/v1/insights").get("window");
        assertThat(Instant.parse(defaults.get("to").asString())).isBefore(Instant.now().plusSeconds(1));
        assertThat(defaults.get("from").asString()).endsWith("T00:00:00Z");
        for (var query : List.of("from=2026-11-15T00:00:00Z&to=2026-11-01T00:00:00Z", "from=yesterday",
                "from=2025-01-01T00:00:00Z&to=2026-11-01T00:00:00Z", "top=0", "top=51", "top=x")) {
            error("/api/v1/insights?" + query, 400, "INVALID_FILTER");
        }
    }

    @Test
    void theOpenApiDocumentPublishesTheFiltersAndTheirValues() {
        var document = JSON.readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk().expectBody()
                .returnResult().getResponseBodyContent());
        var parameters = document.get("paths").get("/api/v1/incidents").get("get").get("parameters");
        var names = new HashSet<String>();
        for (var parameter : parameters) names.add(parameter.get("name").asString());
        assertThat(names).contains("state", "pageSize", "pageToken", "pipelineId", "repositoryId", "severity", "stage",
                "status", "diagnosis", "q");
        for (var parameter : parameters) {
            if (parameter.get("name").asString().equals("diagnosis")) {
                assertThat(texts(parameter.get("schema").get("items").get("enum")))
                        .containsExactly("PENDING", "CONFIRMED", "CORRECTED", "REJECTED", "ABSENT");
            }
        }
        assertThat(document.get("paths").has("/api/v1/incidents/summary")).isTrue();
        assertThat(document.get("paths").has("/api/v1/insights")).isTrue();
    }

    private UUID pipeline(String githubRepository, String fullName, String workflowId, String workflowName, boolean grant) {
        var repository = UUID.randomUUID();
        var pipeline = UUID.randomUUID();
        repositories.add(repository);
        pipelines.add(pipeline);
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, ?, now())",
                repository, githubRepository, fullName);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                        criticality, monitoring_status, created_at)
                VALUES (?, ?, ?, ?, 'workflow.yml', 'HIGH', 'ACTIVE', now())
                """, pipeline, repository, workflowId, workflowName);
        if (grant) {
            jdbc.update("""
                    INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                    VALUES (?, ?, ?, 'VIEW', 'filters-fixture', now())
                    """, UUID.randomUUID(), pipeline, ACTOR);
        }
        return pipeline;
    }

    /** Opens an incident through the application, then sets its fields; each one is newer than the previous. */
    private UUID incident(UUID pipeline, String status, String severity, PipelineStage stage) {
        var execution = UUID.randomUUID();
        int position = order++;
        jdbc.update("""
                INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha,
                    external_status, external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, ?, 1, 'main', 'sha', 'completed', 'failure', true, now(), now(), 'filters-origin', now())
                """, execution, pipeline, "run-" + position + "-" + execution);
        var opened = incidents.handle(new OpenIncidentCommand(pipeline, execution, stage, "Filters incident " + position,
                BASE, "filters-" + position));
        jdbc.update("UPDATE ops.incidents SET created_at = ?, status = ?::ops.\"IncidentStatus\", severity = ?::ops.\"Severity\", "
                        + "resolved_at = CASE WHEN ? = 'RESOLVED' THEN ?::timestamptz ELSE NULL END WHERE id = ?",
                Timestamp.from(BASE.plusSeconds(position)), status, severity, status, Timestamp.from(BASE.plusSeconds(3600)),
                opened.getId());
        origins.put(opened.getId(), execution);
        return opened.getId();
    }

    private UUID resolved(UUID pipeline, PipelineStage stage, String detected, String resolvedAt) {
        var id = incident(pipeline, "RESOLVED", "HIGH", stage);
        jdbc.update("UPDATE ops.incidents SET detected_at = ?, resolved_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse(detected)), Timestamp.from(Instant.parse(resolvedAt)), id);
        return id;
    }

    private void diagnose(UUID incident, UUID pipeline, String repository, String status, int revision, String createdAt) {
        var body = summary(incident, pipeline, repository, UUID.randomUUID(), 1, status, revision, null);
        body.put("createdAt", createdAt);
        put(incident, body).expectStatus().isOk();
    }

    private Map<String, Object> summary(UUID incident, UUID pipeline, String repository, UUID diagnosis, int version,
                                        String status, int revision, String cause) {
        var body = new LinkedHashMap<String, Object>();
        body.put("diagnosisId", diagnosis.toString());
        body.put("version", version);
        body.put("executionId", origins.getOrDefault(incident, UUID.randomUUID()).toString());
        body.put("pipelineId", pipeline.toString());
        body.put("githubRepositoryId", repository);
        body.put("status", status);
        body.put("reviewRevision", revision);
        body.put("reviewedAt", revision == 0 ? null : "2026-11-02T01:00:00Z");
        body.put("probableCause", cause);
        body.put("createdAt", "2026-11-02T00:00:00Z");
        return body;
    }

    private RestTestClient.ResponseSpec put(UUID incident, Map<String, Object> body) {
        return put(incident, body, TestIdentity.workload("recommendation", SUMMARY_ROLE));
    }

    private RestTestClient.ResponseSpec put(UUID incident, Map<String, Object> body, String token) {
        var request = client.put().uri("/internal/v1/incidents/" + incident + "/diagnosis-summary")
                .contentType(MediaType.APPLICATION_JSON).header("X-Correlation-ID", "summary-fixture");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.body(body).exchange();
    }

    private JsonNode get(String uri, Object... variables) {
        return JSON.readTree(client.get().uri(uri, variables).header("Authorization", ApiContractTests.bearer()).exchange()
                .expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*")
                .expectBody().returnResult().getResponseBodyContent());
    }

    private void error(String uri, int status, String code, Object... variables) {
        client.get().uri(uri, variables).header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isEqualTo(status)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON).expectBody().jsonPath("$.code").isEqualTo(code);
    }

    private static List<UUID> ids(JsonNode page) {
        var ids = new ArrayList<UUID>();
        for (var item : page.get("items")) ids.add(UUID.fromString(item.get("id").asString()));
        return ids;
    }

    private static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    private static Map<String, Long> counts(JsonNode object) {
        var counts = new LinkedHashMap<String, Long>();
        for (var name : object.propertyNames()) counts.put(name, object.get(name).asLong());
        return counts;
    }
}
