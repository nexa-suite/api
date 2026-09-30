package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Canonical application boundary for append-only security audit events. */
@org.springframework.modulith.NamedInterface(value = "access-contracts", propagate = false)
public interface SecurityAuditPort {
@org.springframework.modulith.NamedInterface(value = "access-contracts", propagate = false)
    record Event(String type, UUID actorUserId, UUID targetUserId, UUID tenantId, UUID workspaceId,
            String surface, String correlationId, String traceId, Instant occurredAt, Map<String, Object> metadata) {}

    void append(Event event);
}
