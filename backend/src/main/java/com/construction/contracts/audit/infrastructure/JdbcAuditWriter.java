package com.construction.contracts.audit.infrastructure;

import com.construction.contracts.audit.application.AuditWriter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditWriter implements AuditWriter {
    private final JdbcTemplate jdbc;

    JdbcAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Long organizationId, Long actorId, String action, String resourceType,
                       long resourceId, String summary) {
        String requestId = MDC.get("requestId");
        jdbc.update("""
            INSERT INTO audit_events (organization_id, actor_id, action, resource_type, resource_id, request_id, summary)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, organizationId, actorId, action, resourceType, resourceId,
            requestId == null ? UUID.randomUUID().toString() : requestId, summary);
    }
}
