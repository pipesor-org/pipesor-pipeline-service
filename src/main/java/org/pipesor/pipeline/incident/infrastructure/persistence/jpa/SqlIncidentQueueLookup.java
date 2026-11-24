package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.pipesor.pipeline.incident.application.ports.IncidentQueueLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter.DiagnosisFilter;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter.SeverityFilter;
import org.pipesor.pipeline.incident.application.queries.InsightsView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queue, summary and Insights reads (ADR-027). The authorization (granted pipeline and visible repository) and every
 * filter are SQL predicates of one statement, so paging, counting and aggregation only ever see visible incidents.
 * Enum values are written as literals from the Java enums (never from request text); identifiers and text are bound.
 */
@Repository
public class SqlIncidentQueueLookup implements IncidentQueueLookup {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate snapshot;

    public SqlIncidentQueueLookup(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.snapshot = new TransactionTemplate(transactions);
        // The page and its total come from the same snapshot.
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    @Override
    public Page queue(String actorRef, Set<String> repositories, IncidentFilter filter, ListIncidentQueueQuery.Cursor after, int limit) {
        if (repositories.isEmpty()) return new Page(List.of(), 0);
        var parameters = new MapSqlParameterSource();
        String where = where(actorRef, repositories, filter.effectiveStatuses(), filter, parameters);
        String page = where;
        if (after != null) {
            page += "  AND (i.created_at, i.id) < (:afterCreatedAt, :afterId)\n";
            parameters.addValue("afterCreatedAt", OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC)).addValue("afterId", after.id());
        }
        parameters.addValue("limit", limit);
        String rows = IncidentRows.SELECT + page + "ORDER BY i.created_at DESC, i.id DESC\nLIMIT :limit";
        String count = "SELECT count(*)\n" + IncidentRows.FROM + where;
        return snapshot.execute(status -> new Page(jdbc.query(rows, parameters, IncidentRows::view),
                Objects.requireNonNull(jdbc.queryForObject(count, parameters, Long.class))));
    }

    @Override
    public List<Count> counts(String actorRef, Set<String> repositories, IncidentFilter filter) {
        if (repositories.isEmpty()) return List.of();
        var parameters = new MapSqlParameterSource();
        String sql = "SELECT i.severity::text AS severity, i.status::text AS status, d.status AS diagnosis, count(*) AS n\n"
                + IncidentRows.FROM + where(actorRef, repositories, filter.effectiveStatuses(), filter, parameters)
                + "GROUP BY 1, 2, 3";
        return jdbc.query(sql, parameters, (row, index) -> {
            var severity = row.getString("severity");
            var diagnosis = row.getString("diagnosis");
            return new Count(severity == null ? SeverityFilter.UNCLASSIFIED : SeverityFilter.valueOf(severity),
                    IncidentStatus.valueOf(row.getString("status")),
                    diagnosis == null ? DiagnosisFilter.ABSENT : DiagnosisFilter.valueOf(diagnosis), row.getLong("n"));
        });
    }

    @Override
    public void resolved(String actorRef, Set<String> repositories, Instant from, Instant to, Consumer<InsightsView.Row> sink) {
        if (repositories.isEmpty()) return;
        var parameters = new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        String sql = """
                SELECT i.id, i.pipeline_id, i.stage::text AS stage, i.detected_at, i.resolved_at,
                       r.github_repository_id, r.full_name, p.github_workflow_id, p.workflow_name,
                       d.status AS diagnosis_status, d.diagnosis_created_at
                """ + IncidentRows.FROM
                + where(actorRef, repositories, Set.of(IncidentStatus.RESOLVED), IncidentFilter.of(ListIncidentQueueQuery.State.RESOLVED), parameters)
                + "  AND i.resolved_at >= :from AND i.resolved_at <= :to\n";
        jdbc.query(sql, parameters, row -> {
            var diagnosis = row.getString("diagnosis_status");
            sink.accept(new InsightsView.Row(row.getObject("id", UUID.class), row.getObject("pipeline_id", UUID.class),
                    PipelineStage.valueOf(row.getString("stage")), IncidentRows.instant(row, "detected_at"),
                    IncidentRows.instant(row, "resolved_at"), IncidentRows.repository(row), IncidentRows.workflow(row),
                    diagnosis == null ? DiagnosisFilter.ABSENT : DiagnosisFilter.valueOf(diagnosis),
                    IncidentRows.instant(row, "diagnosis_created_at")));
        });
    }

    /** Authorization first (grant and visible repository), then each filter; values of one filter are OR-ed. */
    private static String where(String actorRef, Set<String> repositories, Set<IncidentStatus> statuses, IncidentFilter filter,
                                MapSqlParameterSource parameters) {
        var sql = new StringBuilder("""
                WHERE r.github_repository_id IN (:repositories)
                  AND EXISTS (SELECT 1 FROM ops.pipeline_access_grants g
                              WHERE g.pipeline_id = p.id AND g.principal_ref = :actor)
                """);
        parameters.addValue("repositories", repositories).addValue("actor", actorRef);
        sql.append("  AND i.status IN (").append(literals(statuses)).append(")\n");
        if (!filter.pipelineIds().isEmpty()) {
            sql.append("  AND i.pipeline_id IN (:pipelineIds)\n");
            parameters.addValue("pipelineIds", filter.pipelineIds());
        }
        if (!filter.severities().isEmpty()) {
            var conditions = new ArrayList<String>();
            var levels = filter.severities().stream().filter(value -> value != SeverityFilter.UNCLASSIFIED).toList();
            if (!levels.isEmpty()) conditions.add("i.severity IN (" + literals(levels) + ")");
            if (filter.severities().contains(SeverityFilter.UNCLASSIFIED)) conditions.add("i.severity IS NULL");
            sql.append("  AND (").append(String.join(" OR ", conditions)).append(")\n");
        }
        if (!filter.stages().isEmpty()) {
            sql.append("  AND i.stage IN (").append(literals(filter.stages())).append(")\n");
        }
        if (!filter.diagnoses().isEmpty()) {
            var conditions = new ArrayList<String>();
            var reviewed = filter.diagnoses().stream().filter(value -> value != DiagnosisFilter.ABSENT).toList();
            if (!reviewed.isEmpty()) conditions.add("d.status IN (" + literals(reviewed) + ")");
            if (filter.diagnoses().contains(DiagnosisFilter.ABSENT)) conditions.add("d.incident_id IS NULL");
            sql.append("  AND (").append(String.join(" OR ", conditions)).append(")\n");
        }
        var q = filter.q();
        if (q != null && q.match() == IncidentFilter.TextMatch.DISPLAY_NUMBER) {
            sql.append("  AND i.display_number = :displayNumber\n");
            parameters.addValue("displayNumber", q.displayNumber());
        } else if (q != null) {
            sql.append("  AND (r.full_name ILIKE :text ESCAPE '\\' OR p.workflow_name ILIKE :text ESCAPE '\\')\n");
            parameters.addValue("text", "%" + likeEscaped(q.value()) + "%");
        }
        return sql.toString();
    }

    private static String literals(Collection<? extends Enum<?>> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Empty enum filter");
        return String.join(", ", values.stream().map(value -> "'" + value.name() + "'").toList());
    }

    private static String likeEscaped(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
