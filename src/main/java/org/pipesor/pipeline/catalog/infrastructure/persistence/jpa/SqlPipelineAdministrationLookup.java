package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.ports.PipelineAdministrationLookup;
import org.pipesor.pipeline.catalog.application.queries.PipelineActivityView;
import org.pipesor.pipeline.catalog.application.queries.PipelineGrantView;
import org.pipesor.pipeline.catalog.application.services.PipelineAuditMetadata;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SqlPipelineAdministrationLookup implements PipelineAdministrationLookup {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public SqlPipelineAdministrationLookup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<PipelineGrantView> grants(UUID id, int offset, int limit) {
        return jdbc.query("""
                SELECT id, principal_ref, capability::text, created_at FROM ops.pipeline_access_grants
                WHERE pipeline_id = ? ORDER BY created_at, id OFFSET ? LIMIT ?
                """, (row, index) -> new PipelineGrantView(row.getObject(1, UUID.class), row.getString(2),
                AccessCapability.valueOf(row.getString(3)), row.getTimestamp(4).toInstant()), id, offset, limit);
    }

    @Override
    public List<PipelineActivityView> activities(UUID id, int offset, int limit) {
        return jdbc.query("""
                SELECT id, action, actor_ref, occurred_at, correlation_id, before_state::text, after_state::text
                FROM audit.audit_events WHERE entity_type = 'pipeline' AND entity_id = ?
                  AND category = 'access_and_governance_events'
                  AND action IN ('REGISTER', 'CONFIGURE', 'CONFIGURE_POLICY', 'CONFIGURE_OWNERSHIP', 'GRANT', 'REVOKE')
                ORDER BY occurred_at DESC, id DESC OFFSET ? LIMIT ?
                """, (row, index) -> new PipelineActivityView(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                row.getTimestamp(4).toInstant(), row.getString(5), metadata(row.getString(6)), metadata(row.getString(7))), id.toString(), offset, limit);
    }

    private Map<String, Object> metadata(String value) {
        return value == null ? null : PipelineAuditMetadata.sanitizedHistory(json.readValue(value, new TypeReference<Map<String, Object>>() {}));
    }
}
