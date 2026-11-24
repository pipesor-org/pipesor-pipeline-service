package org.pipesor.pipeline.incident.interfaces.rest.transform;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.InvalidIncidentFilter;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

/**
 * Reads the queue filters (ADR-027) from the raw query string. Each filter may repeat; values are taken verbatim (no
 * comma splitting) and must be exact contract tokens, so an unknown or empty value is {@code INVALID_FILTER} instead
 * of being ignored.
 */
public final class IncidentFilterParameters {
    public static final String PIPELINE_ID = "pipelineId";
    public static final String REPOSITORY_ID = "repositoryId";
    public static final String SEVERITY = "severity";
    public static final String STAGE = "stage";
    public static final String STATUS = "status";
    public static final String DIAGNOSIS = "diagnosis";
    public static final String Q = "q";

    private IncidentFilterParameters() {}

    public static IncidentFilter parse(ListIncidentQueueQuery.State state, HttpServletRequest request) {
        var q = values(request, Q);
        if (q.size() > 1) throw new InvalidIncidentFilter("q must not repeat");
        return new IncidentFilter(state,
                parsed(request, PIPELINE_ID, IncidentFilterParameters::uuid),
                values(request, REPOSITORY_ID),
                parsed(request, SEVERITY, value -> token(IncidentFilter.SeverityFilter.class, value)),
                parsed(request, STAGE, value -> token(PipelineStage.class, value)),
                parsed(request, STATUS, value -> token(IncidentStatus.class, value)),
                parsed(request, DIAGNOSIS, value -> token(IncidentFilter.DiagnosisFilter.class, value)),
                q.isEmpty() ? null : IncidentFilter.TextQuery.parse(q.getFirst()));
    }

    private static List<String> values(HttpServletRequest request, String name) {
        var raw = request.getParameterValues(name);
        if (raw == null) return List.of();
        if (raw.length > IncidentFilter.MAX_VALUES) throw new InvalidIncidentFilter("Too many values");
        var values = new ArrayList<String>(raw.length);
        for (var value : raw) {
            if (value == null || value.isEmpty()) throw new InvalidIncidentFilter("Empty filter value");
            values.add(value);
        }
        return values;
    }

    private static <T> List<T> parsed(HttpServletRequest request, String name, Function<String, T> parser) {
        return values(request, name).stream().map(parser).toList();
    }

    private static UUID uuid(String value) {
        try {
            var id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException invalid) {
            // reported below
        }
        throw new InvalidIncidentFilter("Invalid pipelineId");
    }

    private static <E extends Enum<E>> E token(Class<E> type, String value) {
        for (var constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) return constant;
        }
        throw new InvalidIncidentFilter("Unknown filter value");
    }
}
