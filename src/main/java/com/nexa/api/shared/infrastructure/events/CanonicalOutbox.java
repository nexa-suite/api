package com.nexa.api.shared.infrastructure.events;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Single SQL authority for durable cross-context events. */
public final class CanonicalOutbox {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CanonicalOutbox() { }

    public static UUID append(JdbcTemplate jdbc, String eventType, String aggregateType, UUID aggregateId,
                              UUID tenantId, UUID workspaceId, Instant occurredAt, String correlationId,
                              UUID causationId, String schemaVersion, Map<String, Object> payload) {
        return append(jdbc, eventType, aggregateType, aggregateId, tenantId, workspaceId, occurredAt,
                correlationId, causationId, schemaVersion, null, payload);
    }

    /**
     * Persists a canonical event. The optional occurrence key is required when
     * the same event type may happen more than once for one aggregate. The old
     * overload intentionally keeps its historical identity for immutable
     * replay compatibility.
     */
    public static UUID append(JdbcTemplate jdbc, String eventType, String aggregateType, UUID aggregateId,
                              UUID tenantId, UUID workspaceId, Instant occurredAt, String correlationId,
                              UUID causationId, String schemaVersion, String occurrenceKey,
                              Map<String, Object> payload) {
        UUID eventId = eventId(eventType, aggregateType, aggregateId, occurrenceKey);
        try {
            jdbc.update("insert into integration.outbox_event(event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,correlation_id,causation_id,schema_version,payload) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?::jsonb) on conflict (event_id) do nothing",
                    eventId, eventType, aggregateType, aggregateId, tenantId, workspaceId,
                    Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
                    bounded(correlationId, "event"), causationId, bounded(schemaVersion, "1.0"), JSON.writeValueAsString(payload == null ? Map.of() : payload));
            return eventId;
        } catch (Exception exception) {
            throw new IllegalStateException("Canonical outbox event could not be persisted", exception);
        }
    }

    static UUID eventId(String eventType, String aggregateType, UUID aggregateId, String occurrenceKey) {
        String identity = eventType + ":" + aggregateType + ":" + aggregateId
                + (occurrenceKey == null || occurrenceKey.isBlank() ? "" : ":" + occurrenceKey.strip());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static String bounded(String value, String fallback) {
        String safe = value == null || value.isBlank() ? fallback : value.strip();
        return safe.length() <= 160 ? safe : safe.substring(0, 160);
    }
}
