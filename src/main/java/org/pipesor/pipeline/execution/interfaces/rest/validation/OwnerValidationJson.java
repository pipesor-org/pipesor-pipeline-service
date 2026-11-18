package org.pipesor.pipeline.execution.interfaces.rest.validation;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class OwnerValidationJson {
    public static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> REQUEST = Set.of("requestId","correlationId","deadlineAt","tenantId",
            "githubAccountId","githubInstallationId","githubRepositoryId","githubWorkflowId","scopeRevision",
            "githubRunId","runAttempt","pipelineId","executionId","incidentId");

    public record Request(UUID requestId, Instant deadlineAt, OperationalReferenceQuery query) {
        @Override public String toString() { return "OwnerValidationRequest[protected]"; }
    }

    private OwnerValidationJson() {}

    public static Request request(byte[] body, String correlation) {
        var node = object(body, REQUEST);
        String trace = text(node,"correlationId");
        require(trace.equals(correlation));
        String deadline = text(node,"deadlineAt");
        require(deadline.endsWith("Z"));
        var attempt = node.get("runAttempt");
        require(attempt.isIntegralNumber() && attempt.canConvertToInt());
        return new Request(uuid(node,"requestId"), parseDeadline(deadline),
                new OperationalReferenceQuery(uuid(node,"tenantId"), text(node,"githubAccountId"),
                        text(node,"githubInstallationId"), text(node,"githubRepositoryId"),
                        text(node,"githubWorkflowId"), text(node,"scopeRevision"), text(node,"githubRunId"),
                        attempt.intValue(),uuid(node,"pipelineId"),uuid(node,"executionId"),uuid(node,"incidentId"),trace));
    }

    public static String decision(byte[] body, UUID id, String correlation) {
        var node = object(body, Set.of("requestId","correlationId","decision"));
        require(id.equals(uuid(node,"requestId")) && correlation.equals(text(node,"correlationId")));
        String result = text(node,"decision");
        require(Set.of("AUTHORIZED","DENIED").contains(result));
        return result;
    }

    public static JsonNode object(byte[] body, Set<String> keys) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var node = JSON.readTree(value);
            require(node != null && node.isObject() && node.propertyNames().equals(keys));
            return node;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid owner validation request");
        }
    }

    public static String text(JsonNode node, String name) {
        var value = node.get(name);
        require(value != null && value.isString() && !value.asString().isBlank());
        return value.asString();
    }

    private static Instant parseDeadline(String value) {
        try { return Instant.parse(value); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid deadline"); }
    }

    private static UUID uuid(JsonNode node, String name) {
        String raw = text(node,name);
        var value = UUID.fromString(raw);
        require(value.toString().equals(raw) && !value.equals(new UUID(0,0)));
        return value;
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid owner validation request");
    }
}
