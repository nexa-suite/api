package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsEventContextQueryPort;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantLogisticsEventContextQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates BC-06 event snapshots over the exact router-owned Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantLogisticsEventContextQueryFactory implements TenantLogisticsEventContextQueryFactory {
    @Override
    public LogisticsEventContextQueryPort bindTo(JdbcTemplate tenantJdbc) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        return new LogisticsEventContextQueryPort() {
            @Override
            public Optional<DispatchSnapshot> findDispatch(UUID tenantId, UUID workspaceId, UUID dispatchOrderId) {
                return jdbc.query("select id,inventory_reservation_id,sales_order_id,client_account_id,version "
                                + "from logistics.dispatch_order where tenant_id=? and workspace_id=? and id=?",
                        rs -> rs.next() ? Optional.of(snapshot(rs)) : Optional.empty(),
                        tenantId, workspaceId, dispatchOrderId);
            }

            @Override
            public Optional<DispatchSnapshot> findDispatchByReservation(UUID tenantId, UUID workspaceId,
                    UUID reservationId) {
                List<DispatchSnapshot> matches = jdbc.query("select id,inventory_reservation_id,sales_order_id,client_account_id,version "
                                + "from logistics.dispatch_order where tenant_id=? and workspace_id=? "
                                + "and inventory_reservation_id=?",
                        (rs, row) -> snapshot(rs), tenantId, workspaceId, reservationId);
                if (matches.size() > 1) throw new IllegalStateException("Ambiguous Tenant dispatch context");
                return matches.stream().findFirst();
            }

            private DispatchSnapshot snapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
                return new DispatchSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("inventory_reservation_id", UUID.class),
                        rs.getObject("sales_order_id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version"));
            }
        };
    }
}
