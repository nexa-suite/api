package com.nexa.api.notifications.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads the exact Tenant outbox fact that a notification projection claims to represent. */
public interface NotificationProjectionSourceEventQuery {
    Optional<SourceEvent> find(UUID eventId, UUID tenantId, UUID workspaceId);

    record SourceEvent(String eventType, String aggregateType, UUID aggregateId, UUID tenantId,
                       UUID workspaceId, Instant occurredAt, String payloadText) { }
}
