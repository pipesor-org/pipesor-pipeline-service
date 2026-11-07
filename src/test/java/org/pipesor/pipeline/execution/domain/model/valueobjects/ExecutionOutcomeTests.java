package org.pipesor.pipeline.execution.domain.model.valueobjects;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertAll;

class ExecutionOutcomeTests {

    @Test
    void comparesExecutionFactsByValueRatherThanObjectIdentity() {
        UUID executionId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        var failed = new ExecutionOutcome(executionId, pipelineId, true);

        assertThat(failed).isEqualTo(new ExecutionOutcome(
                UUID.fromString(executionId.toString()), UUID.fromString(pipelineId.toString()), true));
        assertThat(failed).isNotEqualTo(new ExecutionOutcome(UUID.randomUUID(), pipelineId, true));
        assertThat(failed).isNotEqualTo(new ExecutionOutcome(executionId, UUID.randomUUID(), true));
        assertThat(failed).isNotEqualTo(new ExecutionOutcome(executionId, pipelineId, false));
    }

    @Test
    void requiresBothExecutionAndPipelineReferences() {
        assertAll(
                () -> assertThatNullPointerException().isThrownBy(() ->
                        new ExecutionOutcome(null, UUID.randomUUID(), true)),
                () -> assertThatNullPointerException().isThrownBy(() ->
                        new ExecutionOutcome(UUID.randomUUID(), null, true)));
    }
}
