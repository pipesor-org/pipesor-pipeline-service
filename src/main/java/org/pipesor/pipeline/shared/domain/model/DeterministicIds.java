package org.pipesor.pipeline.shared.domain.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

public final class DeterministicIds {
    private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private DeterministicIds() {}

    public static UUID pipeline(String repository, String workflow) {
        return named("urn:pipesor:v1:pipeline:github:" + external(repository) + ":" + external(workflow));
    }

    public static UUID execution(String repository, String run, int attempt) {
        if (attempt < 1) { throw new IllegalArgumentException("Positive run attempt required"); }
        return named("urn:pipesor:v1:execution:github:" + external(repository) + ":" + external(run) + ":" + attempt);
    }

    public static UUID incident(UUID execution) {
        return named("urn:pipesor:v1:incident:" + Objects.requireNonNull(execution));
    }

    private static String external(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Canonical positive decimal external ID required");
        }
        return value;
    }

    private static UUID named(String name) {
        try {
            var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits()).array());
            var bytes = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
