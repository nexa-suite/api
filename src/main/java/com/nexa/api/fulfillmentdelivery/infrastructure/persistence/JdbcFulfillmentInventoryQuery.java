package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcFulfillmentInventoryQuery implements FulfillmentInventoryQuery {
    private final JdbcTemplate jdbc;
    public JdbcFulfillmentInventoryQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean hasFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.fulfillment where tenant_id=? and workspace_id=? and sales_order_id=?)",
                Boolean.class, tenantId, workspaceId, salesOrderId));
    }

    @Override
    public boolean hasActiveDispatch(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.dispatch_order where tenant_id=? and workspace_id=? and sales_order_id=? and status<>'CANCELLED')",
                Boolean.class, tenantId, workspaceId, salesOrderId));
    }

    @Override
    public Optional<UUID> physicalAllocationForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        return jdbc.query("select f.physical_allocation_id from logistics.delivery d join logistics.fulfillment f "
                        + "on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? and f.physical_allocation_id is not null",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, deliveryId).stream().findFirst();
    }
}
