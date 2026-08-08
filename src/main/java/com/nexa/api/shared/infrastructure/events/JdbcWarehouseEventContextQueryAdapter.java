package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.warehouse.application.port.out.WarehouseEventContextQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACL adapter translating the warehouse read model into event published language. */
@Repository
@Profile("!test")
public class JdbcWarehouseEventContextQueryAdapter implements WarehouseEventContextQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcWarehouseEventContextQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReservationSnapshot> findActiveReservationForSalesOrder(UUID tenantId, UUID workspaceId,
                                                                               UUID salesOrderId) {
        List<ReservationSnapshot> matches = jdbc.query("select id,sales_order_id,status,version "
                        + "from warehouse.inventory_reservation where tenant_id=? and workspace_id=? "
                        + "and sales_order_id=? and status in ('PENDING','RESERVED') "
                        + "order by created_at,id",
                (rs, row) -> reservation(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                        rs.getString("status"), rs.getLong("version")), tenantId, workspaceId, salesOrderId);
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous active reservation context");
        return matches.stream().findFirst();
    }

    @Override
    public Optional<ReservationSnapshot> findReservationForSalesOrder(UUID tenantId, UUID workspaceId,
                                                                        UUID salesOrderId) {
        return jdbc.query("select id,sales_order_id,status,version "
                        + "from warehouse.inventory_reservation where tenant_id=? and workspace_id=? "
                        + "and sales_order_id=? order by created_at desc,id desc limit 1",
                rs -> rs.next()
                        ? Optional.of(reservation(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                        rs.getString("status"), rs.getLong("version")))
                        : Optional.empty(), tenantId, workspaceId, salesOrderId);
    }

    @Override
    public Optional<ReservationSnapshot> findReservation(UUID tenantId, UUID workspaceId, UUID reservationId) {
        return jdbc.query("select id,sales_order_id,status,version from warehouse.inventory_reservation "
                        + "where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next()
                        ? Optional.of(reservation(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                        rs.getString("status"), rs.getLong("version")))
                        : Optional.empty(), tenantId, workspaceId, reservationId);
    }

    private static ReservationSnapshot reservation(UUID id, UUID salesOrderId, String status, long version) {
        return new ReservationSnapshot(id, salesOrderId, status, version);
    }
}
