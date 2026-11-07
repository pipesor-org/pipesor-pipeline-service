package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.application.ports.PipelineGrantStore;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class SqlPipelineAccess implements PipelineAccess, PipelineGrantStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public SqlPipelineAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); }

    @Override
    public void require(String actorRef, UUID pipelineId, AccessCapability capability) {
        if (actorRef == null || actorRef.isBlank()) {
            throw new SecurityException("Authenticated principal required");
        }
        // Hold the granting rows until the command commits, including against revocation.
        var grants = jdbc.queryForList("""
                SELECT capability::text FROM ops.pipeline_access_grants
                WHERE pipeline_id = ? AND principal_ref = ?
                  AND (capability::text = ? OR ? = 'VIEW') FOR SHARE
                """, String.class, pipelineId, actorRef, capability.name(), capability.name());
        if (grants.isEmpty()) {
            throw new SecurityException("Pipeline capability required: " + capability);
        }
    }
    @Override
    public boolean change(ChangePipelineAccessCommand command,
                          UUID grantId, Instant now) {
        if (command.grant()) {
            return jdbc.update("""
                    INSERT INTO ops.pipeline_access_grants
                      (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                    VALUES (?, ?, ?, ?::ops."AccessCapability", ?, ?)
                    ON CONFLICT (pipeline_id, principal_ref, capability) DO NOTHING
                    """, grantId, command.pipelineId(), command.principalRef(), command.capability().name(),
                    command.correlationId(), Timestamp.from(now)) == 1;
        }
        return jdbc.update("""
                DELETE FROM ops.pipeline_access_grants
                WHERE pipeline_id = ? AND principal_ref = ? AND capability::text = ?
                """, command.pipelineId(), command.principalRef(), command.capability().name()) == 1;
    }

    @Override
    public Map<UUID, Set<AccessCapability>> capabilities(String actor, Set<UUID> pipelines) {
        var result = new HashMap<UUID, Set<AccessCapability>>();
        if (pipelines.isEmpty()) { return result; }
        var parameters = Map.of("actor", actor, "pipelines", pipelines);
        named.query("""
                SELECT pipeline_id, capability::text FROM ops.pipeline_access_grants
                WHERE principal_ref = :actor AND pipeline_id IN (:pipelines)
                """, parameters, (RowCallbackHandler) row -> {
                    var granted = result.computeIfAbsent(row.getObject(1, UUID.class), id -> EnumSet.noneOf(AccessCapability.class));
                    granted.add(AccessCapability.valueOf(row.getString(2)));
                    granted.add(AccessCapability.VIEW);
                });
        result.replaceAll((id, capabilities) -> Set.copyOf(capabilities));
        return result;
    }

}
