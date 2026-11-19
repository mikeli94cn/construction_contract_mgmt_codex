package com.construction.contracts.audit.application;

public interface AuditWriter {
    void record(Long organizationId, Long actorId, String action, String resourceType,
                long resourceId, String summary);
}
