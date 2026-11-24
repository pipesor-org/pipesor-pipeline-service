package org.pipesor.pipeline.incident.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentRunRole;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaIncidentLookup implements IncidentLookup {
    private final EntityManager em;
    private final NamedParameterJdbcTemplate jdbc;

    public JpaIncidentLookup(EntityManager em, NamedParameterJdbcTemplate jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IncidentView> findById(UUID id) {
        return jdbc.query(IncidentRows.SELECT + "WHERE i.id = :id", new MapSqlParameterSource("id", id), IncidentRows::view)
                .stream().findFirst();
    }

    @Override
    public List<IncidentView> list(UUID pipelineId, int offset, int limit) {
        var parameters = new MapSqlParameterSource("offset", offset).addValue("limit", limit);
        String where = "";
        if (pipelineId != null) {
            where = "WHERE i.pipeline_id = :parent\n";
            parameters.addValue("parent", pipelineId);
        }
        return jdbc.query(IncidentRows.SELECT + where + "ORDER BY i.created_at, i.id OFFSET :offset LIMIT :limit",
                parameters, IncidentRows::view);
    }

    @Override
    public Optional<UUID> originExecution(UUID incidentId) {
        return em.createQuery("""
                select r.executionId from IncidentRunJpaEntity r
                where r.incidentId = :id and r.role = :role
                """, UUID.class).setParameter("id", incidentId).setParameter("role", IncidentRunRole.ORIGIN)
                .getResultStream().findFirst();
    }

    @Override
    public List<IncidentRunView> runs(UUID incidentId, int offset, int limit) {
        return em.createQuery("select r from IncidentRunJpaEntity r where r.incidentId = :id order by r.createdAt, r.id", IncidentRunJpaEntity.class)
                .setParameter("id", incidentId).setFirstResult(offset).setMaxResults(limit).getResultStream()
                .map(entity -> new IncidentRunView(entity.getId(), entity.incidentId, entity.executionId, entity.role, entity.correlationId, entity.getCreatedAt())).toList();
    }
}
