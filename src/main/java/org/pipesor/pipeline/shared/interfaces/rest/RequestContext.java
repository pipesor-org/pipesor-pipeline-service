package org.pipesor.pipeline.shared.interfaces.rest;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class RequestContext {
    private RequestContext() {}

    public static String principal(String reference) {
        var parts = reference.split(":", -1);
        if (parts.length != 2) { throw new IllegalArgumentException("Expected tenantUUID:objectUUID principal"); }
        var canonical = UUID.fromString(parts[0]) + ":" + UUID.fromString(parts[1]);
        if (!canonical.equalsIgnoreCase(reference)) { throw new IllegalArgumentException("Canonical principal UUIDs required"); }
        return canonical;
    }

    public static String actor(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tid")) + ":" + UUID.fromString(jwt.getClaimAsString("oid"));
    }
}
