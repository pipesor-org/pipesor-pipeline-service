package org.pipesor.pipeline.incident.domain.model.aggregates;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.execution.domain.model.valueobjects.ExecutionOutcome;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentDetails;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class IncidentTests {

    @Test
    void createsAnIncidentWithOneFailedOriginFromItsPipeline() {
        UUID incidentId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        var origin = new ExecutionOutcome(UUID.randomUUID(), UUID.fromString(pipelineId.toString()), true);

        Incident incident = Incident.fromFailedExecution(incidentId, pipelineId, origin, details());

        assertThat(incident.getId()).isEqualTo(incidentId);
        assertThat(incident.getPipelineId()).isEqualTo(pipelineId);
        assertThat(incident.getOrigin()).isEqualTo(origin);
    }

    @Test
    void rejectsAnExecutionThatDidNotFail() {
        UUID pipelineId = UUID.randomUUID();
        var origin = new ExecutionOutcome(UUID.randomUUID(), pipelineId, false);

        assertThatIllegalArgumentException().isThrownBy(() ->
                Incident.fromFailedExecution(UUID.randomUUID(), pipelineId, origin, details()));
    }

    @Test
    void rejectsAFailedOriginFromAnotherPipeline() {
        var origin = new ExecutionOutcome(UUID.randomUUID(), UUID.randomUUID(), true);

        assertThatIllegalArgumentException().isThrownBy(() ->
                Incident.fromFailedExecution(UUID.randomUUID(), UUID.randomUUID(), origin, details()));
    }

    @Test
    void rejectsAnIncidentWithoutAnOrigin() {
        assertThatNullPointerException().isThrownBy(() ->
                Incident.fromFailedExecution(UUID.randomUUID(), UUID.randomUUID(), null, details()));
    }

    @Test
    void requiresAnIncidentIdentity() {
        UUID pipelineId = UUID.randomUUID();
        var origin = new ExecutionOutcome(UUID.randomUUID(), pipelineId, true);

        assertThatNullPointerException().isThrownBy(() ->
                Incident.fromFailedExecution(null, pipelineId, origin, details()));
    }

    @Test
    void requiresTheIncidentPipeline() {
        var origin = new ExecutionOutcome(UUID.randomUUID(), UUID.randomUUID(), true);

        assertThatNullPointerException().isThrownBy(() ->
                Incident.fromFailedExecution(UUID.randomUUID(), null, origin, details()));
    }
    private IncidentDetails details() {
        return new IncidentDetails(PipelineStage.BUILD, "Sanitized build failure",
                Instant.parse("2026-11-06T12:00:00.123456Z"), "test-correlation",
                Instant.parse("2026-11-06T12:01:00.654321Z"));
    }
}
