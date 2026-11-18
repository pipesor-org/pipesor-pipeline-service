package org.pipesor.pipeline.configuration;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pipeline.owner-validation")
public record OwnerValidationProperties(
        URI issuer, URI jwkSetUri, String tenant, String audience, String callerClientId,
        String callerPrincipalId, String role, Duration clockSkew, Duration requestTimeout,
        int maxRequestBytes, int maxResponseBytes, int maxInFlight,
        URI ingestionUri, String ingestionScope, Duration connectTimeout) {
    public OwnerValidationProperties {
        if (!https(issuer) || !https(jwkSetUri) || !https(ingestionUri)
                || !"/internal/v1/capture/machine-scope-checks".equals(ingestionUri.getPath())
                || !uuid(tenant) || !uuid(callerClientId) || !uuid(callerPrincipalId)
                || audience == null || audience.isBlank() || role == null || role.isBlank()
                || ingestionScope == null || ingestionScope.isBlank()
                || clockSkew == null || clockSkew.isNegative()
                || !positive(requestTimeout) || !positive(connectTimeout)
                || maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE
                || maxResponseBytes < 1 || maxResponseBytes == Integer.MAX_VALUE || maxInFlight < 1) {
            throw new IllegalArgumentException("Complete owner validation settings are required");
        }
        requestTimeout.toNanos();
        connectTimeout.toNanos();
    }

    private static boolean positive(Duration value) { return value != null && !value.isNegative() && !value.isZero(); }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value) && !UUID.fromString(value).equals(new UUID(0,0)); }
        catch (RuntimeException invalid) { return false; }
    }
    private static boolean https(URI value) {
        return value != null && "https".equals(value.getScheme()) && value.getHost() != null
                && value.getUserInfo() == null && value.getQuery() == null && value.getFragment() == null;
    }
    @Override public String toString() { return "OwnerValidationProperties[configured]"; }
}
