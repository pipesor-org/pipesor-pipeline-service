package org.pipesor.pipeline.incident.application.queries;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.pipesor.pipeline.incident.domain.model.valueobjects.DiagnosisStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.Severity;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

/**
 * Normalized incident queue criteria (ADR-027): the lifecycle {@code state} plus optional filters. Values of one filter
 * are OR-ed and different filters AND-ed; they are applied together with the authorization before paging. Lists are
 * deduplicated and sorted so that equal criteria have one canonical form (page tokens are bound to it).
 */
public record IncidentFilter(
        ListIncidentQueueQuery.State state,
        List<UUID> pipelineIds,
        List<String> repositoryIds,
        List<SeverityFilter> severities,
        List<PipelineStage> stages,
        List<IncidentStatus> statuses,
        List<DiagnosisFilter> diagnoses,
        TextQuery q,
        Instant resolvedFrom,
        Instant resolvedTo) {

    public static final int MAX_VALUES = 100;
    private static final Pattern REPOSITORY_ID = Pattern.compile("[1-9][0-9]{0,19}");

    /** Severity values; {@code UNCLASSIFIED} selects incidents without severity. */
    public enum SeverityFilter {
        CRITICAL, HIGH, MEDIUM, LOW, UNCLASSIFIED;

        public Severity severity() { return this == UNCLASSIFIED ? null : Severity.valueOf(name()); }
    }

    /** Diagnosis review status; {@code ABSENT} selects incidents without a projected diagnosis. */
    public enum DiagnosisFilter {
        PENDING, CONFIRMED, CORRECTED, REJECTED, ABSENT;

        public DiagnosisStatus status() { return this == ABSENT ? null : DiagnosisStatus.valueOf(name()); }
    }

    public enum TextMatch { DISPLAY_NUMBER, TEXT }

    /**
     * Free-text query, normalized (NFKC, trimmed, inner whitespace collapsed). {@code INC-123} or {@code 123} matches the
     * display number exactly. Other text is split into words: an incident matches when every word is a case-insensitive
     * substring of the repository full name or the workflow name (each word may match a different field).
     */
    public record TextQuery(String value, TextMatch match, Long displayNumber, List<String> words) {
        public static final int MIN_TEXT_LENGTH = 2;
        public static final int MAX_LENGTH = 100;
        private static final Pattern DISPLAY_NUMBER = Pattern.compile("(?i)(?:INC-)?([0-9]{1,18})");
        private static final Pattern WHITESPACE = Pattern.compile("\\s+");

        public static TextQuery parse(String raw) {
            if (raw == null) throw new InvalidIncidentFilter("q must not be empty");
            if (raw.codePoints().anyMatch(Character::isISOControl)) throw new InvalidIncidentFilter("q has control characters");
            var value = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC).strip()).replaceAll(" ");
            int length = value.codePointCount(0, value.length());
            if (length == 0 || length > MAX_LENGTH) throw new InvalidIncidentFilter("q length out of bounds");
            var number = DISPLAY_NUMBER.matcher(value);
            if (number.matches()) return new TextQuery(value, TextMatch.DISPLAY_NUMBER, Long.parseLong(number.group(1)), List.of());
            if (length < MIN_TEXT_LENGTH) throw new InvalidIncidentFilter("q is too short");
            var words = new ArrayList<String>();
            for (var word : value.toLowerCase(Locale.ROOT).split(" ")) {
                if (!words.contains(word)) words.add(word);
            }
            return new TextQuery(value, TextMatch.TEXT, null, words);
        }

        public TextQuery {
            Objects.requireNonNull(value);
            Objects.requireNonNull(match);
            words = List.copyOf(words);
            if ((match == TextMatch.DISPLAY_NUMBER) != (displayNumber != null) || (match == TextMatch.TEXT) == words.isEmpty()) {
                throw new IllegalArgumentException("Inconsistent query");
            }
        }

        String canonical() {
            return match == TextMatch.DISPLAY_NUMBER ? "#" + displayNumber : "~" + String.join(" ", words);
        }
    }

    public IncidentFilter {
        Objects.requireNonNull(state);
        pipelineIds = sorted(pipelineIds, Comparator.comparing(UUID::toString));
        repositoryIds = sorted(repositoryIds, Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        severities = sorted(severities, Comparator.naturalOrder());
        stages = sorted(stages, Comparator.naturalOrder());
        statuses = sorted(statuses, Comparator.naturalOrder());
        diagnoses = sorted(diagnoses, Comparator.naturalOrder());
        for (var repository : repositoryIds) {
            if (!REPOSITORY_ID.matcher(repository).matches()) throw new InvalidIncidentFilter("Invalid repositoryId");
        }
        for (var status : statuses) {
            if (!state.statuses().contains(status)) throw new InvalidIncidentFilter("status not valid for state");
        }
        if ((resolvedFrom != null || resolvedTo != null) && state != ListIncidentQueueQuery.State.RESOLVED) {
            throw new InvalidIncidentFilter("Resolution window requires state RESOLVED");
        }
        if (resolvedFrom != null && resolvedTo != null && resolvedFrom.isAfter(resolvedTo)) {
            throw new InvalidIncidentFilter("resolvedFrom must not follow resolvedTo");
        }
    }

    /** Only the lifecycle state, as the queue behaved before the filters existed. */
    public static IncidentFilter of(ListIncidentQueueQuery.State state) {
        return new IncidentFilter(state, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null);
    }

    /** True when no optional filter is set. */
    public boolean unfiltered() {
        return pipelineIds.isEmpty() && repositoryIds.isEmpty() && severities.isEmpty() && stages.isEmpty()
                && statuses.isEmpty() && diagnoses.isEmpty() && q == null && resolvedFrom == null && resolvedTo == null;
    }

    /** The lifecycle statuses to read: the {@code status} filter, or every status of the state. */
    public Set<IncidentStatus> effectiveStatuses() {
        return statuses.isEmpty() ? state.statuses() : EnumSet.copyOf(statuses);
    }

    /**
     * Repositories to read: the actor's visible repositories, narrowed by the {@code repositoryId} filter. A filtered
     * repository the actor cannot see contributes nothing, so filters never widen the authorization.
     */
    public Set<String> scope(Set<String> visible) {
        if (repositoryIds.isEmpty()) return Set.copyOf(visible);
        var scoped = new HashSet<>(repositoryIds);
        scoped.retainAll(visible);
        return Set.copyOf(scoped);
    }

    /** Stable text of the normalized criteria; equal criteria give equal text. */
    public String canonical() {
        return String.join(";", "state=" + state, "pipelineId=" + join(pipelineIds), "repositoryId=" + join(repositoryIds),
                "severity=" + join(severities), "stage=" + join(stages), "status=" + join(statuses),
                "diagnosis=" + join(diagnoses), "q=" + (q == null ? "" : q.canonical()),
                "resolvedFrom=" + (resolvedFrom == null ? "" : resolvedFrom), "resolvedTo=" + (resolvedTo == null ? "" : resolvedTo));
    }

    private static String join(List<?> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }

    private static <T> List<T> sorted(Collection<T> values, Comparator<? super T> order) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_VALUES) throw new InvalidIncidentFilter("Too many filter values");
        var unique = new ArrayList<T>();
        for (var value : values) {
            Objects.requireNonNull(value);
            if (!unique.contains(value)) unique.add(value);
        }
        unique.sort(order);
        return List.copyOf(unique);
    }
}
