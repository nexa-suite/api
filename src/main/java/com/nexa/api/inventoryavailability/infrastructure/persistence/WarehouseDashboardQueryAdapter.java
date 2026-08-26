package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseDashboardQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Owns the Warehouse readiness/dashboard SQL projection; no command mutation is performed here. */
@Repository
@Profile("!test")
public class WarehouseDashboardQueryAdapter implements WarehouseDashboardQueryPort {
    private final JdbcTemplate jdbc;

    public WarehouseDashboardQueryAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<WarehouseOperationsService.ReadinessCandidate> readiness(CurrentAccessContext context) {
        context.requirePermission(Permission.FULFILLMENT_READ);
        return jdbc.query(
                "select r.id,r.sales_order_id,r.order_number,r.client_account_id,r.status,r.reserved_at,r.expires_at," +
                        "count(distinct l.id) line_count,coalesce(sum(a.quantity),0) total_reserved_quantity " +
                        "from warehouse.inventory_reservation r " +
                        "join warehouse.inventory_reservation_line l on l.reservation_id=r.id " +
                        "left join warehouse.inventory_reservation_allocation a on a.reservation_line_id=l.id " +
                        "where r.tenant_id=? and r.workspace_id=? and r.status='RESERVED' " +
                        "group by r.id,r.sales_order_id,r.order_number,r.client_account_id,r.status,r.reserved_at,r.expires_at " +
                        "order by r.reserved_at asc,r.id asc limit 100",
                (rs, row) -> new WarehouseOperationsService.ReadinessCandidate(
                        rs.getObject("id").toString(), rs.getObject("sales_order_id").toString(),
                        rs.getString("order_number"), rs.getObject("client_account_id").toString(),
                        rs.getInt("line_count"), rs.getBigDecimal("total_reserved_quantity"),
                        instant(rs, "reserved_at"), instant(rs, "expires_at"), "INVENTORY_RESERVED"),
                uuid(context.tenantId().toString()), uuid(context.workspaceId().toString()));
    }
}
