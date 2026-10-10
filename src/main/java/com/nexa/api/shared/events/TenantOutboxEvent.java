package com.nexa.api.shared.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Immutable, scope-bound view of one Tenant outbox fact. */
public record TenantOutboxEvent(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
                                UUID tenantId, UUID workspaceId, Instant occurredAt, String correlationId,
                                UUID causationId, String schemaVersion, String payload,
                                Instant createdAt, String payloadSha256) {
    public TenantOutboxEvent(UUID eventId, String eventType, String aggregateType, UUID aggregateId,
                             UUID tenantId, UUID workspaceId, Instant occurredAt, String correlationId,
                             UUID causationId, String schemaVersion, String payload, Instant createdAt) {
        this(eventId, eventType, aggregateType, aggregateId, tenantId, workspaceId, occurredAt, correlationId,
                causationId, schemaVersion, payload, createdAt, sha256(payload));
    }

    public TenantOutboxEvent {
        Objects.requireNonNull(eventId, "Outbox event id is required");
        Objects.requireNonNull(eventType, "Outbox event type is required");
        Objects.requireNonNull(aggregateType, "Outbox aggregate type is required");
        Objects.requireNonNull(aggregateId, "Outbox aggregate id is required");
        Objects.requireNonNull(tenantId, "Outbox Tenant scope is required");
        Objects.requireNonNull(workspaceId, "Outbox Workspace scope is required");
        Objects.requireNonNull(occurredAt, "Outbox occurrence time is required");
        Objects.requireNonNull(payload, "Outbox payload is required");
        Objects.requireNonNull(createdAt, "Outbox creation time is required");
        if (payloadSha256 == null || !payloadSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Outbox payload SHA-256 is invalid");
        }
    }

    public static String sha256(String payload) {
        Objects.requireNonNull(payload, "Outbox payload is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
