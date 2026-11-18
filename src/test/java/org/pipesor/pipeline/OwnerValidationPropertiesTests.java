package org.pipesor.pipeline;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.configuration.OwnerValidationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnerValidationPropertiesTests {
    private static final String LOG_EVENT_CLIENT = "4166972a-7490-433e-a5cf-3725d6ff823c";
    private static final String LOG_EVENT_PRINCIPAL = "08abff88-1291-47a2-b797-17b862032e95";
    private static final String CORRELATION_CLIENT = "3086a1a3-f6ef-47ae-a049-cf9549c96f48";
    private static final String CORRELATION_PRINCIPAL = "b624214f-7c7e-4688-b49a-53f1c7aeb346";
    private static final String CORRELATION_ROLE = "Correlation.OperationalReferences.Check";

    @Test
    void keepsCorrelationOptionalAndAcceptsAnExplicitDistinctCaller() {
        assertThat(settings(null, null, null).correlationClientId()).isNull();
        var configured = settings(CORRELATION_CLIENT, CORRELATION_PRINCIPAL, CORRELATION_ROLE);
        assertThat(configured.correlationRole()).isEqualTo(CORRELATION_ROLE);
        assertThat(configured.toString()).doesNotContain(CORRELATION_CLIENT, CORRELATION_PRINCIPAL);
    }

    @Test
    void refusesPartialOrInvalidCallerConfiguration() {
        for (int mask = 1; mask < 7; mask++) {
            String client = (mask & 1) == 0 ? null : CORRELATION_CLIENT;
            String principal = (mask & 2) == 0 ? null : CORRELATION_PRINCIPAL;
            String role = (mask & 4) == 0 ? null : CORRELATION_ROLE;
            assertThrows(IllegalArgumentException.class, () -> settings(client, principal, role));
        }
        assertThrows(IllegalArgumentException.class, () -> settings("not-a-uuid", CORRELATION_PRINCIPAL, CORRELATION_ROLE));
        assertThrows(IllegalArgumentException.class, () -> settings(CORRELATION_CLIENT, "", CORRELATION_ROLE));
        assertThrows(IllegalArgumentException.class, () -> settings(CORRELATION_CLIENT, CORRELATION_PRINCIPAL, " "));
    }

    @Test
    void refusesReusingTheRegistrationCallerIdentityOrPermission() {
        assertThrows(IllegalArgumentException.class, () -> settings(LOG_EVENT_CLIENT, CORRELATION_PRINCIPAL, CORRELATION_ROLE));
        assertThrows(IllegalArgumentException.class, () -> settings(CORRELATION_CLIENT, LOG_EVENT_PRINCIPAL, CORRELATION_ROLE));
        assertThrows(IllegalArgumentException.class, () -> settings(CORRELATION_CLIENT, CORRELATION_PRINCIPAL, "Evidence.Check"));
    }

    private static OwnerValidationProperties settings(String client, String principal, String role) {
        return new OwnerValidationProperties(
                URI.create("https://identity.test/tenant/v2.0"), URI.create("https://identity.test/keys"),
                "31bb0907-a72d-422e-ad83-96355c46b2c3", "pipeline-machine",
                LOG_EVENT_CLIENT, LOG_EVENT_PRINCIPAL, "Evidence.Check", Duration.ZERO, Duration.ofSeconds(3),
                2048, 1024, 1, URI.create("https://ingestion.test/internal/v1/capture/machine-scope-checks"),
                "api://ingestion-machine/.default", Duration.ofSeconds(1), client, principal, role);
    }
}
