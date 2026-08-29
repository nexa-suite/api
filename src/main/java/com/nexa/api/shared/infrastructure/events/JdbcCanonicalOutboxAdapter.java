package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Shared infrastructure implementation of the canonical outbox boundary. */
@Repository
@Profile("!test")
public class JdbcCanonicalOutboxAdapter implements CanonicalOutboxPort {
    private final JdbcTemplate jdbc;

    public JdbcCanonicalOutboxAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID append(String eventType, String aggregateType, UUID aggregateId,
                       UUID tenantId, UUID workspaceId, Instant occurredAt, String correlationId,
                       UUID causationId, String schemaVersion, Map<String, Object> payload) {
        return CanonicalOutbox.append(jdbc, eventType, aggregateType, aggregateId, tenantId, workspaceId,
                occurredAt, correlationId, causationId, schemaVersion, payload);
    }
}
