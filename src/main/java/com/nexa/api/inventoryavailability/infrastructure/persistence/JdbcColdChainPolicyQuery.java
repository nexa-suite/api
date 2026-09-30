package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Reads the zone policy attached to the lots in a physical allocation. */
@Repository
@Profile("!test")
public class JdbcColdChainPolicyQuery implements ColdChainPolicyQuery {
    private final JdbcTemplate jdbc;
    private final InventoryFulfillmentSource fulfillmentSource;

    public JdbcColdChainPolicyQuery(JdbcTemplate jdbc, InventoryFulfillmentSource fulfillmentSource) {
        this.jdbc = jdbc;
        this.fulfillmentSource = fulfillmentSource;
    }

    @Override
    public Optional<Range> rangeForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        Optional<UUID> allocationId = fulfillmentSource.physicalAllocationForDelivery(tenantId, workspaceId, deliveryId);
        if (allocationId.isEmpty()) return Optional.empty();
        var ranges = jdbc.query("select z.temperature_min,z.temperature_max,'CELSIUS' from warehouse.physical_allocation a "
                        + "join warehouse.physical_allocation_line l on l.tenant_id=a.tenant_id and l.workspace_id=a.workspace_id and l.physical_allocation_id=a.id "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "where a.tenant_id=? and a.workspace_id=? and a.id=? and z.temperature_min is not null and z.temperature_max is not null "
                        + "order by l.id",
                (rs, row) -> new Range(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)),
                tenantId, workspaceId, allocationId.get());
        if (ranges.isEmpty() || ranges.stream().anyMatch(range -> !range.equals(ranges.get(0)))) return Optional.empty();
        return Optional.of(ranges.get(0));
    }

    @Override
    public Optional<Range> rangeForDeliveryAndLot(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId) {
        Optional<UUID> allocationId = fulfillmentSource.physicalAllocationForDelivery(tenantId, workspaceId, deliveryId);
        if (allocationId.isEmpty()) return Optional.empty();
        return jdbc.query("select z.temperature_min,z.temperature_max,'CELSIUS' from warehouse.physical_allocation a "
                        + "join warehouse.physical_allocation_line l on l.tenant_id=a.tenant_id and l.workspace_id=a.workspace_id and l.physical_allocation_id=a.id and l.lot_id=? "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "where a.tenant_id=? and a.workspace_id=? and a.id=? and z.temperature_min is not null and z.temperature_max is not null",
                (rs, row) -> new Range(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)),
                lotId, tenantId, workspaceId, allocationId.get()).stream().findFirst();
    }

    @Override
    public boolean lotIsAllocatedToDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID lotId) {
        Optional<UUID> allocationId = fulfillmentSource.physicalAllocationForDelivery(tenantId, workspaceId, deliveryId);
        if (allocationId.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from warehouse.physical_allocation_line l "
                        + "where l.tenant_id=? and l.workspace_id=? and l.physical_allocation_id=? and l.lot_id=?)", Boolean.class,
                tenantId, workspaceId, allocationId.get(), lotId));
    }
}
