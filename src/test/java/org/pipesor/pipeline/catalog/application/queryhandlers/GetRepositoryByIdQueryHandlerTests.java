package org.pipesor.pipeline.catalog.application.queryhandlers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.queries.GetRepositoryByIdQuery;
import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class GetRepositoryByIdQueryHandlerTests {

    @Test
    void delegatesTheExactUuidAndReturnsRepositoryData() {
        UUID id = UUID.randomUUID();
        var repository = new GitHubRepository(id, "12345678901234567890", "example/service",
                null, Instant.parse("2026-11-06T12:00:00Z"));
        var handler = new GetRepositoryByIdQueryHandler(requestedId -> {
            assertThat(requestedId).isEqualTo(id);
            return Optional.of(repository);
        });

        assertThat(handler.handle(new GetRepositoryByIdQuery(id))).hasValueSatisfying(result ->
                assertThat(result).usingRecursiveComparison().isEqualTo(repository));
    }

    @Test
    void returnsEmptyWhenTheRepositoryDoesNotExist() {
        var handler = new GetRepositoryByIdQueryHandler(id -> Optional.empty());

        assertThat(handler.handle(new GetRepositoryByIdQuery(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void rejectsQueriesWithoutAnIdentity() {
        assertThatNullPointerException().isThrownBy(() -> new GetRepositoryByIdQuery(null));
    }
}
