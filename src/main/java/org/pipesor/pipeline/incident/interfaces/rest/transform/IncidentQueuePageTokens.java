package org.pipesor.pipeline.incident.interfaces.rest.transform;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;

/** Opaque page tokens for the incident queue: base64url of the last row's position. */
public final class IncidentQueuePageTokens {
    private static final String VERSION = "q1";

    private IncidentQueuePageTokens() {}

    public static String encode(ListIncidentQueueQuery.Cursor cursor) {
        if (cursor == null) return null;
        String raw = VERSION + "|" + cursor.createdAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ListIncidentQueueQuery.Cursor decode(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            if (token.length() > 256) throw new IllegalArgumentException();
            var parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw new IllegalArgumentException();
            var id = UUID.fromString(parts[2]);
            if (!id.toString().equals(parts[2])) throw new IllegalArgumentException();
            return new ListIncidentQueueQuery.Cursor(Instant.parse(parts[1]), id);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid page token");
        }
    }
}
