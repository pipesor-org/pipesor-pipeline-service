package org.pipesor.pipeline.incident.interfaces.rest.transform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;

/**
 * Opaque page tokens for the incident queue: base64url of the last row's position plus a digest of the normalized
 * state, filters and order (ADR-027), so a token only continues the listing it came from. Tokens of the earlier unbound
 * format ({@code q1}) are still accepted for an unfiltered queue.
 */
public final class IncidentQueuePageTokens {
    private static final String LEGACY = "q1";
    private static final String VERSION = "q2";
    private static final int MAX_LENGTH = 256;

    private IncidentQueuePageTokens() {}

    public static String encode(ListIncidentQueueQuery.Cursor cursor, IncidentFilter filter, ListIncidentQueueQuery.Sort sort) {
        if (cursor == null) return null;
        String raw = VERSION + "|" + cursor.at() + "|" + cursor.id() + "|" + digest(filter, sort);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ListIncidentQueueQuery.Cursor decode(String token, IncidentFilter filter, ListIncidentQueueQuery.Sort sort) {
        if (token == null || token.isEmpty()) return null;
        String[] parts;
        try {
            if (token.length() > MAX_LENGTH) throw new IllegalArgumentException();
            parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidPageToken();
        }
        boolean bound = parts.length == 4 && VERSION.equals(parts[0]) && digest(filter, sort).equals(parts[3]);
        boolean legacy = parts.length == 3 && LEGACY.equals(parts[0]) && filter.unfiltered()
                && sort == ListIncidentQueueQuery.Sort.CREATED_AT;
        if (!bound && !legacy) throw new InvalidPageToken();
        try {
            var id = UUID.fromString(parts[2]);
            if (!id.toString().equals(parts[2])) throw new IllegalArgumentException();
            return new ListIncidentQueueQuery.Cursor(Instant.parse(parts[1]), id);
        } catch (RuntimeException invalid) {
            throw new InvalidPageToken();
        }
    }

    private static String digest(IncidentFilter filter, ListIncidentQueueQuery.Sort sort) {
        try {
            var canonical = filter.canonical() + ";sort=" + sort.token();
            var hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 12));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
