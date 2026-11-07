package org.pipesor.pipeline.execution.interfaces.messaging.transform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class ExecutionSnapshotTransformer {
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public ApplyExecutionSnapshotCommand fromJson(String payload) {
        return fromJson(payload.getBytes(StandardCharsets.UTF_8));
    }

    public ApplyExecutionSnapshotCommand fromJson(byte[] payload) {
        var snapshot = json.readValue(payload, ExecutionSnapshot.class);
        return new ApplyExecutionSnapshotCommand(snapshot, digest(snapshot), digest(new Content(snapshot.pipeline(),
                snapshot.execution(), snapshot.commit(), snapshot.service(), snapshot.environment(), snapshot.stages(), snapshot.failure())));
    }

    private String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private record Content(ExecutionSnapshot.Pipeline pipeline, ExecutionSnapshot.Execution execution,
            ExecutionSnapshot.Commit commit, String service, String environment,
            List<ExecutionSnapshot.Stage> stages, ExecutionSnapshot.Failure failure) {}
}
