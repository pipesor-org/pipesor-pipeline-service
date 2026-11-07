package org.pipesor.pipeline.catalog.application.services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public final class PipelineAuditMetadata {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|bearer\\s+\\S+|-----BEGIN .*PRIVATE KEY-----|(?:password|secret|token|credential)\\s*[:=]|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", Pattern.CASE_INSENSITIVE);
    private PipelineAuditMetadata() {}

    public static Map<String, String> of(PipelineView view) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("snapshotVersion", "2");
        fields.put("criticality", view.criticality().name());
        fields.put("monitoringStatus", view.monitoringStatus().name());
        fields.put("environment", safe(view.environment()));
        fields.put("responsibleTeamRef", safe(view.responsibleTeamRef()));
        fields.put("responsibleTeamNameSnapshot", safe(view.responsibleTeamNameSnapshot()));
        return fields;
    }

    /** Whitelist metadata on history reads too; never return arbitrary legacy JSON or provider payloads. */
    public static Map<String, Object> sanitizedHistory(Map<String, Object> input) {
        if (input == null) { return null; }
        var result = new LinkedHashMap<String, Object>();
        var allowed = Set.of("snapshotVersion", "criticality", "monitoringStatus", "environment",
                "responsibleTeamRef", "responsibleTeamNameSnapshot", "repositoryId", "githubRepositoryId",
                "githubWorkflowId", "installationId", "creatorCapability", "principalRef", "capability", "granted");
        input.forEach((key, value) -> {
            if (allowed.contains(key) && (value == null || value instanceof String)) {
                result.put(key, safe((String) value));
            }
        });
        return result;
    }

    public static void validateInputs(String... values) {
        for (String value : values) {
            if (value != null && CREDENTIAL.matcher(value).find()) {
                throw new IllegalArgumentException("Configuration metadata cannot contain credentials");
            }
        }
    }

    private static String safe(String value) {
        return value != null && CREDENTIAL.matcher(value).find() ? "[REDACTED]" : value;
    }
}
