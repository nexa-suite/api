package com.nexa.api.shared.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Named technical boundary for appending to the one durable integration
 * outbox. Bounded contexts depend on this port, never on the shared SQL
 * implementation.
 */
public interface CanonicalOutboxPort {
    UUID append(String eventType, String aggregateType, UUID aggregateId,
                UUID tenantId, UUID workspaceId, Instant occurredAt, String correlationId,
                UUID causationId, String schemaVersion, Map<String, Object> payload);
}
