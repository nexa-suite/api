package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.logistics.application.port.out.LogisticsEventContextQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ACL adapter translating the logistics read model into event published language. */
@Repository
@Profile("!test")
public class JdbcLogisticsEventContextQueryAdapter implements LogisticsEventContextQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcLogisticsEventContextQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DispatchSnapshot> findDispatch(UUID tenantId, UUID workspaceId, UUID dispatchOrderId) {
        return jdbc.query("select id,inventory_reservation_id,sales_order_id,client_account_id,version "
                        + "from logistics.dispatch_order where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next() ? Optional.of(dispatch(rs)) : Optional.empty(), tenantId, workspaceId, dispatchOrderId);
    }

    @Override
    public Optional<DispatchSnapshot> findDispatchByReservation(UUID tenantId, UUID workspaceId, UUID reservationId) {
        List<DispatchSnapshot> matches = jdbc.query("select id,inventory_reservation_id,sales_order_id,client_account_id,version "
                        + "from logistics.dispatch_order where tenant_id=? and workspace_id=? and inventory_reservation_id=?",
                (rs, row) -> dispatch(rs), tenantId, workspaceId, reservationId);
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous dispatch context");
        return matches.stream().findFirst();
    }

    private static DispatchSnapshot dispatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DispatchSnapshot(rs.getObject("id", UUID.class),
                rs.getObject("inventory_reservation_id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                rs.getObject("client_account_id", UUID.class), rs.getLong("version"));
    }
}
