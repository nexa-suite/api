package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.port.out.NotificationProjectionSourceEventQuery;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads one scoped Tenant outbox source fact through the supplied Tenant JDBC session. */
public final class JdbcNotificationProjectionSourceEventQueryAdapter
        implements NotificationProjectionSourceEventQuery {
    private final JdbcTemplate tenantJdbc;

    public JdbcNotificationProjectionSourceEventQueryAdapter(JdbcTemplate tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
    }

    @Override
    public Optional<SourceEvent> find(UUID eventId, UUID tenantId, UUID workspaceId) {
        return tenantJdbc.query("""
                select event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,payload::text
                  from integration.outbox_event
                 where event_id=? and tenant_id=? and workspace_id=?
                """, rs -> rs.next() ? Optional.of(new SourceEvent(rs.getString(1), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                rs.getTimestamp(6).toInstant(), rs.getString(7))) : Optional.empty(), eventId, tenantId, workspaceId);
    }
}
