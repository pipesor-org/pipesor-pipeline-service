package org.pipesor.pipeline;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.configuration.OwnerValidationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnerValidationPropertiesTests {
    @Test
    void appliesBoundedDefaults() {
        var defaults = new OwnerValidationProperties(null, 0, 0, 0);
        assertThat(defaults.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(defaults.maxRequestBytes()).isEqualTo(4096);
        assertThat(defaults.maxInFlight()).isEqualTo(16);
    }

    @Test
    void refusesInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new OwnerValidationProperties(Duration.ZERO, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerValidationProperties(Duration.ofSeconds(1), -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerValidationProperties(Duration.ofSeconds(1), 1, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerValidationProperties(Duration.ofSeconds(1), 1, 1, -2));
    }
}
