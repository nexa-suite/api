package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.SellableSkuPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads the zone policy attached to the lots in a physical allocation. */
@Repository
@Profile("!test")
public class JdbcColdChainPolicyQuery implements ColdChainPolicyQuery {
    private final JdbcTemplate jdbc;
    private final InventoryFulfillmentSource fulfillmentSource;
    private final SellableSkuQuery sellableSkus;

    public JdbcColdChainPolicyQuery(JdbcTemplate jdbc, InventoryFulfillmentSource fulfillmentSource,
                                    SellableSkuQuery sellableSkus) {
        this.jdbc = jdbc;
        this.fulfillmentSource = fulfillmentSource;
        this.sellableSkus = sellableSkus;
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
    public Optional<LotTemperatureContext> temperatureContextForLot(UUID tenantId, UUID workspaceId, UUID lotId) {
        return jdbc.query("select l.id,l.warehouse_id,l.zone_id,z.temperature_min,z.temperature_max,l.sku_id,l.version,l.status inventory_lot_status, "
                        + "exists(select 1 from warehouse.inventory_temperature_evaluation e where e.tenant_id=l.tenant_id "
                        + "and e.workspace_id=l.workspace_id and e.lot_id=l.id and e.status='OPEN' and e.disposition='HOLD' "
                        + "and (e.source_type<>'STOCK_EVIDENCE' or e.blocks_committed_execution)) temperature_hold_open "
                        + "from warehouse.inventory_lot l join warehouse.storage_zone z "
                        + "on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "where l.tenant_id=? and l.workspace_id=? and l.id=?",
                (rs, row) -> {
                    var minimum = rs.getBigDecimal("temperature_min");
                    var maximum = rs.getBigDecimal("temperature_max");
                    Optional<Range> range = minimum == null || maximum == null
                            ? Optional.empty() : Optional.of(new Range(minimum, maximum, "CELSIUS"));
                    return new LotTemperatureContext(rs.getObject("id", UUID.class),
                            rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class), range,
                            rs.getObject("sku_id", UUID.class), rs.getLong("version"),
                            rs.getString("inventory_lot_status"), rs.getBoolean("temperature_hold_open"));
                }, tenantId, workspaceId, lotId).stream().findFirst();
    }

    @Override
    public Optional<SellableSkuPolicy> temperatureRequirementForSku(UUID tenantId, UUID workspaceId, UUID skuId) {
        return sellableSkus.findPhysicalValidationPolicy(tenantId, workspaceId, skuId);
    }

    @Override
    public Optional<Range> commonTemperatureRangeForWarehouse(UUID tenantId, UUID workspaceId, UUID warehouseId) {
        List<Range> ranges = jdbc.query("select temperature_min,temperature_max,'CELSIUS' from warehouse.storage_zone "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and status='ACTIVE' order by id",
                (rs, row) -> {
                    var minimum = rs.getBigDecimal("temperature_min");
                    var maximum = rs.getBigDecimal("temperature_max");
                    return minimum == null || maximum == null ? null : new Range(minimum, maximum, "CELSIUS");
                }, tenantId, workspaceId, warehouseId);
        if (ranges.isEmpty() || ranges.stream().anyMatch(java.util.Objects::isNull)) return Optional.empty();
        Range common = ranges.get(0);
        return ranges.stream().allMatch(common::equals) ? Optional.of(common) : Optional.empty();
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
