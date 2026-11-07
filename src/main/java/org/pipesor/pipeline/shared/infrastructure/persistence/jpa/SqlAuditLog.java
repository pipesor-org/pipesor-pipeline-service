package org.pipesor.pipeline.shared.infrastructure.persistence.jpa;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.pipesor.pipeline.shared.application.ports.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SqlAuditLog implements AuditLog {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public SqlAuditLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void append(UUID id, String entityType, UUID entityId, String action, String actorRef,
                       String correlationId, Instant now, Map<String, String> before, Map<String, String> after) {
        jdbc.update("""
                INSERT INTO audit.audit_events
                  (id, category, correlation_id, actor_ref, entity_type, entity_id, action,
                   before_state, after_state, occurred_at, created_at)
                VALUES (?, 'access_and_governance_events', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, id, correlationId, actorRef, entityType, entityId.toString(), action,
                json.writeValueAsString(before), json.writeValueAsString(after),
                Timestamp.from(now), Timestamp.from(now));
    }
}
