package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Inventory-owned FEFO allocation and physical stock responsibility. */
@Repository
@Profile("!test")
public class WarehousePhysicalAllocationAdapter implements PhysicalAllocationCommands {
    private final JdbcTemplate jdbc;
    private final BusinessTraceabilityCommands traceability;
    private final SalesOrderFulfillmentQuery salesOrders;
    private final SellableSkuQuery sellableSkus;

    @Autowired
    public WarehousePhysicalAllocationAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability,
                                              SalesOrderFulfillmentQuery salesOrders, SellableSkuQuery sellableSkus) {
        this.jdbc = jdbc;
        this.traceability = traceability;
        this.salesOrders = salesOrders;
        this.sellableSkus = sellableSkus;
    }

    public WarehousePhysicalAllocationAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability,
                                              SalesOrderFulfillmentQuery salesOrders) {
        this(jdbc, traceability, salesOrders, null);
    }

    public WarehousePhysicalAllocationAdapter(JdbcTemplate jdbc, BusinessTraceabilityCommands traceability) {
        this(jdbc, traceability, null, null);
    }

    @Override
    public AllocationResult getByFulfillment(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        AllocationHeader allocation = jdbc.query(
                "select id,status,version,inventory_backing_id from warehouse.physical_allocation where tenant_id=? and workspace_id=? and fulfillment_id=?",
                (rs, row) -> new AllocationHeader(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getLong("version"), rs.getObject("inventory_backing_id", UUID.class)),
                tenantId, workspaceId, fulfillmentId).stream().findFirst()
                .orElseThrow(() -> error("PHYSICAL_ALLOCATION_NOT_FOUND", true));
        return load(tenantId, workspaceId, allocation.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForFulfillment(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        if (tenantId == null || workspaceId == null || fulfillmentId == null) {
            throw new IllegalArgumentException("Physical allocation lock scope is incomplete");
        }
        if (lockByFulfillment(tenantId, workspaceId, fulfillmentId) == null) {
            throw error("PHYSICAL_ALLOCATION_NOT_FOUND", true);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AllocationResult allocate(AllocationRequest request) {
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "ALLOCATE", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "ALLOCATE", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }

        if (salesOrders == null) throw error("SALES_ORDER_NOT_FOUND", true);
        SalesOrderFulfillmentQuery.Snapshot order = salesOrders.getForUpdate(
                request.tenantId(), request.workspaceId(), request.salesOrderId());
        if (!"CONFIRMED".equals(order.status())) throw error("SALES_ORDER_NOT_CONFIRMED", false);
        if (!request.commercialCommitmentId().equals(order.commercialCommitmentId())) {
            throw error("BACKING_LINEAGE_INVALID", false);
        }

        BackingRow backing = lockBacking(request.tenantId(), request.workspaceId(), request.inventoryBackingId());
        if (backing == null) throw error("BACKING_NOT_FOUND", true);
        if (!request.commercialCommitmentId().equals(backing.commitmentId())) throw error("BACKING_LINEAGE_INVALID", false);
        if (!"BACKED".equals(backing.status())) throw error("BACKING_NOT_READY", false);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and sales_order_id=? and status not in ('RELEASED','EXPIRED','CANCELLED'))",
                Boolean.class, request.tenantId(), request.workspaceId(), request.salesOrderId()))
                || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.dispatch_order where tenant_id=? and workspace_id=? and sales_order_id=? and status<>'CANCELLED')",
                Boolean.class, request.tenantId(), request.workspaceId(), request.salesOrderId()))) {
            throw error("CANONICAL_FULFILLMENT_CONFLICT", false);
        }

        ExistingAllocation existing = jdbc.query(
                "select id,status,version from warehouse.physical_allocation where tenant_id=? and workspace_id=? and inventory_backing_id=? for update",
                (rs, row) -> new ExistingAllocation(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version")),
                request.tenantId(), request.workspaceId(), request.inventoryBackingId()).stream().findFirst().orElse(null);
        if (existing != null) throw error("PHYSICAL_ALLOCATION_ALREADY_EXISTS", false);

        List<BackingPosition> positions = backingPositions(request.tenantId(), request.workspaceId(), request.inventoryBackingId());
        validateDemand(request.lines(), positions);
        List<LotRow> lots = lockEligibleLots(request.tenantId(), request.workspaceId(), positions);
        List<SelectedLot> selected = selectFefo(request.lines(), positions, lots);
        if (selected.isEmpty()) throw error("PHYSICAL_ALLOCATION_UNAVAILABLE", false);

        BigDecimal selectedByLine;
        for (PhysicalAllocationCommands.RequestedLine line : request.lines()) {
            selectedByLine = selected.stream().filter(value -> value.matches(line))
                    .map(SelectedLot::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (selectedByLine.compareTo(line.quantity()) != 0) throw error("PHYSICAL_ALLOCATION_UNAVAILABLE", false);
        }

        jdbc.update("insert into warehouse.physical_allocation(id,tenant_id,workspace_id,sales_order_id,commercial_commitment_id,inventory_backing_id,fulfillment_id,status,allocated_at,created_at,updated_at,version,actor_membership_id,idempotency_key,request_hash) values (?,?,?,?,?,?,?,'ALLOCATED',?,?,?,?,?,?,?)",
                request.allocationId(), request.tenantId(), request.workspaceId(), request.salesOrderId(),
                request.commercialCommitmentId(), request.inventoryBackingId(), request.fulfillmentId(),
                timestamp(request.now()), timestamp(request.now()), timestamp(request.now()), 0L,
                request.actorMembershipId(), request.idempotencyKey(), request.requestHash());

        for (SelectedLot value : selected) {
            BigDecimal reservedBefore = value.reserved();
            BigDecimal reservedAfter = reservedBefore.add(value.quantity());
            int updated = jdbc.update("update warehouse.inventory_lot set reserved_quantity=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and stock_quantity-reserved_quantity >= ?",
                    reservedAfter, request.tenantId(), request.workspaceId(), value.lotId(), value.version(), value.quantity());
            if (updated != 1) throw error("CONCURRENCY_CONFLICT", false);
            jdbc.update("insert into warehouse.physical_allocation_line(id,tenant_id,workspace_id,physical_allocation_id,sku_id,catalog_item_id,warehouse_id,zone_id,lot_id,quantity,unit,expiration_date,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.allocationId(), value.skuId(),
                    value.catalogItemId(), value.warehouseId(), value.zoneId(), value.lotId(), value.quantity(), value.unit(),
                    value.expirationDate(), timestamp(request.now()));
            jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), value.warehouseId(), value.zoneId(), value.lotId(),
                    value.catalogItemId(), value.skuId(), "RESERVATION", value.quantity(), value.unit(), value.stock(), value.stock(),
                    reservedBefore, reservedAfter, "Physical allocation", request.actorMembershipId(), request.idempotencyKey(), timestamp(request.now()));
            jdbc.update("insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), value.lotId(), "PHYSICAL_ALLOCATION_CREATED", timestamp(request.now()), request.actorMembershipId(), request.idempotencyKey());
        }

        if (jdbc.update("update warehouse.inventory_backing set status='CONSUMED',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='BACKED'",
                timestamp(request.now()), request.tenantId(), request.workspaceId(), request.inventoryBackingId()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        jdbc.update("insert into warehouse.physical_allocation_event(id,tenant_id,workspace_id,physical_allocation_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), request.allocationId(), "ALLOCATED", request.actorMembershipId(),
                "Inventory backing consumed by physical allocation", timestamp(request.now()));
        CanonicalOutbox.append(jdbc, "PhysicalAllocationCreated.v1", "PhysicalAllocation", request.allocationId(),
                request.tenantId(), request.workspaceId(), request.now(), request.idempotencyKey(), null, "1.0",
                Map.of("allocationId", request.allocationId(), "inventoryBackingId", request.inventoryBackingId(),
                        "fulfillmentId", request.fulfillmentId(), "salesOrderId", request.salesOrderId()));
        trace(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PHYSICAL_ALLOCATION_CREATED",
                request.allocationId(), request.idempotencyKey(), Map.of("inventoryBackingId", request.inventoryBackingId(),
                        "fulfillmentId", request.fulfillmentId(), "salesOrderId", request.salesOrderId()), request.now());
        jdbc.update("insert into warehouse.physical_allocation_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), "ALLOCATE", request.idempotencyKey(),
                request.requestHash(), request.allocationId(), timestamp(request.now()));
        return load(request.tenantId(), request.workspaceId(), request.allocationId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PickingScanValidationResult validatePickingScan(PickingScanValidationRequest request) {
        requireRequest(request.tenantId(), request.workspaceId(), request.fulfillmentId(),
                request.physicalAllocationLineId(), request.skuId(), request.lotId(), request.warehouseId(), request.quantity(),
                request.unit(), request.expectedAllocationVersion(), request.now());
        AllocationHeader allocation = lockByFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        if (allocation == null) return outcome("NOT_ALLOCATED", request);
        if (allocation.version() != request.expectedAllocationVersion()) return outcome("STALE_ALLOCATION", request, allocation.version());
        if (!"ALLOCATED".equals(allocation.status())) return outcome("NOT_ALLOCATED", request, allocation.version());

        if (request.overrideRequested()) {
            UUID currentLotId = allocationLineLot(request.tenantId(), request.workspaceId(), allocation.id(), request.physicalAllocationLineId());
            if (currentLotId != null) lockOverrideLots(request.tenantId(), request.workspaceId(), currentLotId, request.lotId());
        }

        List<AllocationScanRow> rows = jdbc.query("select l.id,l.sku_id,l.catalog_item_id,l.warehouse_id,l.zone_id,l.lot_id,l.quantity,l.released_quantity,l.consumed_quantity,l.unit,"
                        + "lot.status lot_status,lot.expiration_date,lot.unit lot_unit,"
                        + "lot.stock_quantity,lot.reserved_quantity,lot.version lot_version,w.status warehouse_status,z.status zone_status,z.zone_type,"
                        + "lot.temperature_value,"
                        + "z.temperature_min zone_temperature_min,z.temperature_max zone_temperature_max,"
                        + "coalesce(service.service_status,'OPERATIONAL') warehouse_service_status,"
                        + "exists(select 1 from warehouse.inventory_temperature_evaluation evaluation where evaluation.tenant_id=lot.tenant_id "
                        + "and evaluation.workspace_id=lot.workspace_id and evaluation.lot_id=lot.id and evaluation.status='OPEN' "
                        + "and evaluation.disposition='HOLD') temperature_hold,"
                        + "coalesce((select disposition.disposition from warehouse.inventory_lot_disposition disposition "
                        + "where disposition.tenant_id=lot.tenant_id and disposition.workspace_id=lot.workspace_id "
                        + "and disposition.lot_id=lot.id order by disposition.created_at desc,disposition.id desc limit 1),'RELEASE') latest_disposition "
                        + "from warehouse.physical_allocation_line l "
                        + "join warehouse.inventory_lot lot on lot.tenant_id=l.tenant_id and lot.workspace_id=l.workspace_id and lot.id=l.lot_id "
                        + "join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "where l.tenant_id=? and l.workspace_id=? and l.physical_allocation_id=? and l.id=? "
                        + "for update of l,lot,w,z",
                (rs, row) -> new AllocationScanRow(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getObject("lot_id", UUID.class),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("released_quantity"),
                        rs.getBigDecimal("consumed_quantity"), rs.getString("unit"), null, false, rs.getString("lot_status"),
                        rs.getObject("expiration_date", LocalDate.class), rs.getString("lot_unit"),
                        rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("reserved_quantity"), rs.getLong("lot_version"),
                        rs.getString("warehouse_status"), rs.getString("zone_status"), rs.getString("zone_type"),
                        rs.getBigDecimal("temperature_value"), null, null, rs.getBigDecimal("zone_temperature_min"),
                        rs.getBigDecimal("zone_temperature_max"), rs.getString("warehouse_service_status"),
                        rs.getBoolean("temperature_hold"), rs.getString("latest_disposition")),
                request.tenantId(), request.workspaceId(), allocation.id(), request.physicalAllocationLineId());
        if (rows.isEmpty()) return outcome("NOT_ALLOCATED", request, allocation.version());
        AllocationScanRow row = rows.get(0);
        BigDecimal remaining = row.quantity().subtract(row.releasedQuantity()).subtract(row.consumedQuantity()).max(BigDecimal.ZERO);
        if (!row.skuId().equals(request.skuId())) return result("WRONG_SKU", row, remaining, allocation.version());
        row = row.withSkuPolicy(physicalSkuPolicy(request.tenantId(), request.workspaceId(), row.skuId()));
        if (request.warehouseId() != null && !row.warehouseId().equals(request.warehouseId())) {
            return result("WRONG_WAREHOUSE", row, remaining, allocation.version());
        }
        if (!row.unit().equalsIgnoreCase(request.unit()) || !row.lotUnit().equalsIgnoreCase(request.unit())) {
            return result("WRONG_UNIT", row, remaining, allocation.version());
        }
        if (!row.lotId().equals(request.lotId())) {
            if (request.overrideRequested()) {
                return applyFefoOverride(request, allocation, row, remaining);
            }
            return result("WRONG_LOT", row, remaining, allocation.version());
        }
        if (request.overrideRequested()) return result("OVERRIDE_NOT_ALLOWED", row, remaining, allocation.version());
        if (!"ACTIVE".equals(row.skuStatus()) || !row.skuVisible()) {
            return result("NON_SELLABLE", row, remaining, allocation.version());
        }
        if ("EXPIRED".equals(row.lotStatus()) || row.expirationDate() == null
                || !row.expirationDate().isAfter(request.now().atZone(java.time.ZoneOffset.UTC).toLocalDate())) {
            return result("EXPIRED", row, remaining, allocation.version());
        }
        if ("QUARANTINED".equals(row.lotStatus()) || "QUARANTINE".equalsIgnoreCase(row.zoneType())) {
            return result("QUARANTINED", row, remaining, allocation.version());
        }
        if (!"AVAILABLE".equals(row.lotStatus()) || !"ACTIVE".equals(row.warehouseStatus())
                || !"ACTIVE".equals(row.zoneStatus())) {
            return result("NON_SELLABLE", row, remaining, allocation.version());
        }
        if (!"OPERATIONAL".equals(row.warehouseServiceStatus()) || row.temperatureHold()
                || blockedDisposition(row.latestDisposition())
                || !temperatureCompatible(row.skuTemperatureMin(), row.skuTemperatureMax(),
                row.zoneTemperatureMin(), row.zoneTemperatureMax(), row.temperatureValue())) {
            return result("NON_SELLABLE", row, remaining, allocation.version());
        }
        if (request.quantity().compareTo(remaining) > 0) {
            return result("INSUFFICIENT_ALLOCATED_QUANTITY", row, remaining, allocation.version());
        }
        return result("MATCH", row, remaining, allocation.version());
    }

    private PickingScanValidationResult applyFefoOverride(PickingScanValidationRequest request,
                                                           AllocationHeader allocation,
                                                           AllocationScanRow current,
                                                           BigDecimal remaining) {
        String reason = normalizeOverrideReason(request.overrideReason());
        if (request.actorMembershipId() == null || reason == null || remaining.signum() <= 0
                || request.quantity().compareTo(remaining) != 0
                || current.releasedQuantity().signum() != 0 || current.consumedQuantity().signum() != 0) {
            return result("OVERRIDE_NOT_ALLOWED", current, remaining, allocation.version());
        }

        CandidateLot alternative = jdbc.query(
                "select lot.id,lot.sku_id,lot.catalog_item_id,lot.warehouse_id,lot.zone_id,lot.unit,lot.expiration_date,"
                        + "lot.stock_quantity,lot.reserved_quantity,lot.version,lot.status,"
                        + "w.status warehouse_status,z.status zone_status,z.zone_type,lot.temperature_value,"
                        + "z.temperature_min zone_temperature_min,z.temperature_max zone_temperature_max,"
                        + "coalesce(service.service_status,'OPERATIONAL') warehouse_service_status,"
                        + "exists(select 1 from warehouse.inventory_temperature_evaluation evaluation where evaluation.tenant_id=lot.tenant_id "
                        + "and evaluation.workspace_id=lot.workspace_id and evaluation.lot_id=lot.id and evaluation.status='OPEN' "
                        + "and evaluation.disposition='HOLD') temperature_hold,"
                        + "coalesce((select disposition.disposition from warehouse.inventory_lot_disposition disposition "
                        + "where disposition.tenant_id=lot.tenant_id and disposition.workspace_id=lot.workspace_id "
                        + "and disposition.lot_id=lot.id order by disposition.created_at desc,disposition.id desc limit 1),'RELEASE') latest_disposition "
                        + "from warehouse.inventory_lot lot "
                        + "join warehouse.warehouse w on w.tenant_id=lot.tenant_id and w.workspace_id=lot.workspace_id and w.id=lot.warehouse_id "
                        + "join warehouse.storage_zone z on z.tenant_id=lot.tenant_id and z.workspace_id=lot.workspace_id and z.warehouse_id=lot.warehouse_id and z.id=lot.zone_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=lot.tenant_id and service.workspace_id=lot.workspace_id and service.warehouse_id=lot.warehouse_id "
                        + "where lot.tenant_id=? and lot.workspace_id=? and lot.id=? for update of lot",
                (rs, row) -> new CandidateLot(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("unit"), rs.getObject("expiration_date", LocalDate.class), rs.getBigDecimal("stock_quantity"),
                        rs.getBigDecimal("reserved_quantity"), rs.getLong("version"), rs.getString("status"),
                        null, false, rs.getString("warehouse_status"),
                        rs.getString("zone_status"), rs.getString("zone_type"), rs.getBigDecimal("temperature_value"),
                        null, null, rs.getBigDecimal("zone_temperature_min"), rs.getBigDecimal("zone_temperature_max"),
                        rs.getString("warehouse_service_status"), rs.getBoolean("temperature_hold"),
                        rs.getString("latest_disposition")),
                request.tenantId(), request.workspaceId(), request.lotId()).stream().findFirst().orElse(null);
        if (alternative == null) return result("OVERRIDE_NOT_ALLOWED", current, remaining, allocation.version());
        alternative = alternative.withSkuPolicy(physicalSkuPolicy(request.tenantId(), request.workspaceId(), alternative.skuId()));
        if (!current.skuId().equals(alternative.skuId())
                || !current.catalogItemId().equals(alternative.catalogItemId())) {
            return result("WRONG_SKU", current, remaining, allocation.version());
        }
        if (!current.warehouseId().equals(alternative.warehouseId())) {
            return result("WRONG_WAREHOUSE", current, remaining, allocation.version());
        }
        if (!current.unit().equalsIgnoreCase(alternative.unit())
                || !request.unit().equalsIgnoreCase(alternative.unit())) {
            return result("WRONG_UNIT", current, remaining, allocation.version());
        }
        if (!"ACTIVE".equals(alternative.skuStatus()) || !alternative.skuVisible()
                || !"ACTIVE".equals(alternative.warehouseStatus()) || !"ACTIVE".equals(alternative.zoneStatus())) {
            return result("NON_SELLABLE", current, remaining, allocation.version());
        }
        if ("EXPIRED".equals(alternative.status()) || alternative.expirationDate() == null
                || !alternative.expirationDate().isAfter(request.now().atZone(java.time.ZoneOffset.UTC).toLocalDate())) {
            return result("EXPIRED", current, remaining, allocation.version());
        }
        if ("QUARANTINED".equals(alternative.status()) || "QUARANTINE".equalsIgnoreCase(alternative.zoneType())) {
            return result("QUARANTINED", current, remaining, allocation.version());
        }
        if (!"AVAILABLE".equals(alternative.status())) {
            return result("NON_SELLABLE", current, remaining, allocation.version());
        }
        if (!"OPERATIONAL".equals(alternative.warehouseServiceStatus()) || alternative.temperatureHold()
                || blockedDisposition(alternative.latestDisposition())
                || !temperatureCompatible(alternative.skuTemperatureMin(), alternative.skuTemperatureMax(),
                alternative.zoneTemperatureMin(), alternative.zoneTemperatureMax(), alternative.temperatureValue())) {
            return result("NON_SELLABLE", current, remaining, allocation.version());
        }
        if (alternative.stockQuantity().subtract(alternative.reservedQuantity()).compareTo(remaining) < 0) {
            return result("OVERRIDE_NOT_ALLOWED", current, remaining, allocation.version());
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from warehouse.physical_allocation_line where tenant_id=? and workspace_id=? "
                        + "and physical_allocation_id=? and lot_id=? and id<>?)", Boolean.class,
                request.tenantId(), request.workspaceId(), allocation.id(), alternative.id(), current.id()))) {
            return result("OVERRIDE_NOT_ALLOWED", current, remaining, allocation.version());
        }

        int released = jdbc.update(
                "update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                remaining, request.tenantId(), request.workspaceId(), current.lotId(), current.lotVersion(), remaining);
        if (released != 1) throw error("CONCURRENCY_CONFLICT", false);
        int reserved = jdbc.update(
                "update warehouse.inventory_lot set reserved_quantity=reserved_quantity+?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and stock_quantity-reserved_quantity>=?",
                remaining, request.tenantId(), request.workspaceId(), alternative.id(), alternative.version(), remaining);
        if (reserved != 1) throw error("CONCURRENCY_CONFLICT", false);
        if (jdbc.update("update warehouse.physical_allocation_line set zone_id=?,lot_id=?,expiration_date=? "
                        + "where tenant_id=? and workspace_id=? and id=? and physical_allocation_id=? "
                        + "and lot_id=? and released_quantity=0 and consumed_quantity=0",
                alternative.zoneId(), alternative.id(), alternative.expirationDate(), request.tenantId(), request.workspaceId(),
                current.id(), allocation.id(), current.lotId()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        if (jdbc.update("update warehouse.physical_allocation set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? "
                        + "and id=? and status='ALLOCATED' and version=?", timestamp(request.now()), request.tenantId(),
                request.workspaceId(), allocation.id(), allocation.version()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }

        String correlation = "fefo-override-" + allocation.id() + "-" + current.id() + "-" + allocation.version();
        jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), current.warehouseId(), current.zoneId(), current.lotId(),
                current.catalogItemId(), current.skuId(), "RESERVATION_RELEASE", remaining, current.unit(), current.stockQuantity(),
                current.stockQuantity(), current.reservedQuantity(), current.reservedQuantity().subtract(remaining), reason,
                request.actorMembershipId(), correlation, timestamp(request.now()));
        jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), alternative.warehouseId(), alternative.zoneId(), alternative.id(),
                alternative.catalogItemId(), alternative.skuId(), "RESERVATION", remaining, alternative.unit(), alternative.stockQuantity(),
                alternative.stockQuantity(), alternative.reservedQuantity(), alternative.reservedQuantity().add(remaining), reason,
                request.actorMembershipId(), correlation, timestamp(request.now()));
        jdbc.update("insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), alternative.id(), "PHYSICAL_ALLOCATION_FEFO_OVERRIDE",
                timestamp(request.now()), request.actorMembershipId(), correlation);
        jdbc.update("insert into warehouse.physical_allocation_event(id,tenant_id,workspace_id,physical_allocation_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), allocation.id(), "FEFO_OVERRIDE", request.actorMembershipId(),
                reason, timestamp(request.now()));
        trace(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PHYSICAL_ALLOCATION_FEFO_OVERRIDE",
                allocation.id(), correlation, Map.of("fulfillmentId", request.fulfillmentId(), "physicalAllocationLineId", current.id(),
                        "fromLotId", current.lotId(), "toLotId", alternative.id(), "quantity", remaining, "reason", reason), request.now());
        return new PickingScanValidationResult("ALLOWED_OVERRIDE", current.id(), alternative.id(), alternative.warehouseId(),
                current.quantity(), remaining, allocation.version() + 1);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AllocationResult consumeForDispatch(ConsumeRequest request) {
        requireRequest(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), request.now());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "CONSUME", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "CONSUME", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }
        AllocationHeader allocation = lockByFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        if (allocation == null) throw error("PHYSICAL_ALLOCATION_NOT_FOUND", true);
        if (allocation.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT", false);
        if ("CONSUMED".equals(allocation.status())) return load(request.tenantId(), request.workspaceId(), allocation.id());
        if (!"ALLOCATED".equals(allocation.status())) throw error("PHYSICAL_ALLOCATION_NOT_READY", false);
        List<AllocationLot> lines = allocationLots(request.tenantId(), request.workspaceId(), allocation.id());
        for (AllocationLot line : lines) {
            BigDecimal quantity = line.remainingQuantity();
            if (quantity.signum() == 0) continue;
            int updated = jdbc.update("update warehouse.inventory_lot set stock_quantity=stock_quantity-?,reserved_quantity=reserved_quantity-?,status=case when stock_quantity-?=0 then 'DEPLETED' else status end,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and stock_quantity>=? and reserved_quantity>=?",
                    quantity, quantity, quantity, request.tenantId(), request.workspaceId(), line.lotId(), line.version(), quantity, quantity);
            if (updated != 1) throw error("CONCURRENCY_CONFLICT", false);
            jdbc.update("update warehouse.physical_allocation_line set consumed_quantity=consumed_quantity+? where tenant_id=? and workspace_id=? and physical_allocation_id=? and lot_id=?",
                    quantity, request.tenantId(), request.workspaceId(), allocation.id(), line.lotId());
            jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), line.warehouseId(), line.zoneId(), line.lotId(), line.catalogItemId(),
                    line.skuId(), "OUTBOUND_CONSUMPTION", quantity, line.unit(), line.stock(), line.stock().subtract(quantity),
                    line.reserved(), line.reserved().subtract(quantity), "Dispatch physical consumption", request.actorMembershipId(), request.idempotencyKey(), timestamp(request.now()));
            jdbc.update("insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), line.lotId(), "PHYSICAL_ALLOCATION_CONSUMED", timestamp(request.now()), request.actorMembershipId(), request.idempotencyKey());
        }
        if (jdbc.update("update warehouse.physical_allocation set status='CONSUMED',consumed_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='ALLOCATED' and version=?",
                timestamp(request.now()), timestamp(request.now()), request.tenantId(), request.workspaceId(), allocation.id(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        jdbc.update("insert into warehouse.physical_allocation_event(id,tenant_id,workspace_id,physical_allocation_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), allocation.id(), "CONSUMED", request.actorMembershipId(),
                "Physical stock consumed at dispatch", timestamp(request.now()));
        trace(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PHYSICAL_ALLOCATION_CONSUMED",
                allocation.id(), request.idempotencyKey(), Map.of("fulfillmentId", request.fulfillmentId()), request.now());
        jdbc.update("insert into warehouse.physical_allocation_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), "CONSUME", request.idempotencyKey(), request.requestHash(), allocation.id(), timestamp(request.now()));
        return load(request.tenantId(), request.workspaceId(), allocation.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AllocationResult reconcileUnpicked(ReconcileUnpickedRequest request) {
        requireRequest(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey(),
                request.requestHash(), request.now());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "RECONCILE_UNPICKED", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                "RECONCILE_UNPICKED", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.resourceId());
        }

        AllocationHeader allocation = lockByFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        if (allocation == null) throw error("PHYSICAL_ALLOCATION_NOT_FOUND", true);
        if (allocation.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT", false);
        if (!"ALLOCATED".equals(allocation.status())) throw error("PHYSICAL_ALLOCATION_NOT_READY", false);

        Map<LineKey, BigDecimal> requested = new LinkedHashMap<>();
        for (UnpickedLine line : request.lines()) {
            LineKey key = new LineKey(line.skuId(), line.catalogItemId(), line.unit());
            if (requested.put(key, line.quantity()) != null) throw error("FULFILLMENT_LINE_INVALID", false);
        }
        List<AllocationLot> allocationLines = allocationLots(request.tenantId(), request.workspaceId(), allocation.id());
        Map<LineKey, BigDecimal> releasable = allocationLines.stream().collect(Collectors.groupingBy(
                line -> new LineKey(line.skuId(), line.catalogItemId(), line.unit()),
                Collectors.mapping(AllocationLot::remainingQuantity,
                        Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        for (Map.Entry<LineKey, BigDecimal> entry : requested.entrySet()) {
            if (entry.getValue().compareTo(releasable.getOrDefault(entry.getKey(), BigDecimal.ZERO)) > 0) {
                throw error("PHYSICAL_ALLOCATION_UNAVAILABLE", false);
            }
        }

        List<AllocationLot> releaseOrder = allocationLines.stream()
                .sorted(Comparator.comparing(AllocationLot::expirationDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AllocationLot::lotId, Comparator.reverseOrder()))
                .toList();
        for (Map.Entry<LineKey, BigDecimal> entry : requested.entrySet()) {
            BigDecimal remaining = entry.getValue();
            for (AllocationLot line : releaseOrder) {
                if (!line.matches(entry.getKey()) || remaining.signum() <= 0) continue;
                BigDecimal quantity = remaining.min(line.remainingQuantity());
                if (quantity.signum() == 0) continue;
                int updated = jdbc.update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                        quantity, request.tenantId(), request.workspaceId(), line.lotId(), line.version(), quantity);
                if (updated != 1) throw error("CONCURRENCY_CONFLICT", false);
                if (jdbc.update("update warehouse.physical_allocation_line set released_quantity=released_quantity+? where tenant_id=? and workspace_id=? and physical_allocation_id=? and lot_id=? and released_quantity+consumed_quantity+?<=quantity",
                        quantity, request.tenantId(), request.workspaceId(), allocation.id(), line.lotId(), quantity) != 1) {
                    throw error("CONCURRENCY_CONFLICT", false);
                }
                jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), request.tenantId(), request.workspaceId(), line.warehouseId(), line.zoneId(), line.lotId(),
                        line.catalogItemId(), line.skuId(), "RESERVATION_RELEASE", quantity, line.unit(), line.stock(), line.stock(),
                        line.reserved(), line.reserved().subtract(quantity), normalize(request.reason()), request.actorMembershipId(),
                        request.idempotencyKey(), timestamp(request.now()));
                jdbc.update("insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), request.tenantId(), request.workspaceId(), line.lotId(), "PHYSICAL_ALLOCATION_RECONCILED",
                        timestamp(request.now()), request.actorMembershipId(), request.idempotencyKey());
                remaining = remaining.subtract(quantity);
            }
            if (remaining.signum() != 0) throw error("PHYSICAL_ALLOCATION_UNAVAILABLE", false);
        }
        if (jdbc.update("update warehouse.physical_allocation set updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='ALLOCATED' and version=?",
                timestamp(request.now()), request.tenantId(), request.workspaceId(), allocation.id(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        jdbc.update("insert into warehouse.physical_allocation_event(id,tenant_id,workspace_id,physical_allocation_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), allocation.id(), "UNPICKED_RECONCILED",
                request.actorMembershipId(), normalize(request.reason()), timestamp(request.now()));
        trace(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PHYSICAL_ALLOCATION_RECONCILED",
                allocation.id(), request.idempotencyKey(), Map.of("fulfillmentId", request.fulfillmentId(), "reason", normalize(request.reason())), request.now());
        jdbc.update("insert into warehouse.physical_allocation_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), "RECONCILE_UNPICKED", request.idempotencyKey(),
                request.requestHash(), allocation.id(), timestamp(request.now()));
        return load(request.tenantId(), request.workspaceId(), allocation.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(ReleaseRequest request) {
        requireRequest(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), request.now());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "RELEASE", request.idempotencyKey());
        IdempotencyRow prior = idempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "RELEASE", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.requestHash(), request.requestHash());
            return;
        }
        AllocationHeader allocation = lockByFulfillment(request.tenantId(), request.workspaceId(), request.fulfillmentId());
        if (allocation == null) throw error("PHYSICAL_ALLOCATION_NOT_FOUND", true);
        if (allocation.version() != request.expectedVersion()) throw error("CONCURRENCY_CONFLICT", false);
        if ("RELEASED".equals(allocation.status())) return;
        if (!"ALLOCATED".equals(allocation.status())) throw error("PHYSICAL_ALLOCATION_NOT_RELEASEABLE", false);
        List<AllocationLot> lines = allocationLots(request.tenantId(), request.workspaceId(), allocation.id());
        for (AllocationLot line : lines) {
            BigDecimal quantity = line.remainingQuantity();
            if (quantity.signum() == 0) continue;
            int updated = jdbc.update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                    quantity, request.tenantId(), request.workspaceId(), line.lotId(), line.version(), quantity);
            if (updated != 1) throw error("CONCURRENCY_CONFLICT", false);
            if (jdbc.update("update warehouse.physical_allocation_line set released_quantity=released_quantity+? where tenant_id=? and workspace_id=? and physical_allocation_id=? and lot_id=? and released_quantity+consumed_quantity+?<=quantity",
                    quantity, request.tenantId(), request.workspaceId(), allocation.id(), line.lotId(), quantity) != 1) {
                throw error("CONCURRENCY_CONFLICT", false);
            }
            jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), line.warehouseId(), line.zoneId(), line.lotId(), line.catalogItemId(),
                    line.skuId(), "RESERVATION_RELEASE", quantity, line.unit(), line.stock(), line.stock(), line.reserved(),
                    line.reserved().subtract(quantity), request.reason(), request.actorMembershipId(), request.idempotencyKey(), timestamp(request.now()));
            jdbc.update("insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), line.lotId(), "PHYSICAL_ALLOCATION_RELEASED", timestamp(request.now()), request.actorMembershipId(), request.idempotencyKey());
        }
        if (jdbc.update("update warehouse.physical_allocation set status='RELEASED',released_at=?,release_reason=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='ALLOCATED' and version=?",
                timestamp(request.now()), normalize(request.reason()), timestamp(request.now()), request.tenantId(), request.workspaceId(), allocation.id(), request.expectedVersion()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        jdbc.update("insert into warehouse.physical_allocation_event(id,tenant_id,workspace_id,physical_allocation_id,event_type,actor_membership_id,reason,occurred_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), request.tenantId(), request.workspaceId(), allocation.id(), "RELEASED", request.actorMembershipId(), normalize(request.reason()), timestamp(request.now()));
        trace(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "PHYSICAL_ALLOCATION_RELEASED",
                allocation.id(), request.idempotencyKey(), Map.of("fulfillmentId", request.fulfillmentId(), "reason", normalize(request.reason())), request.now());
        jdbc.update("insert into warehouse.physical_allocation_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,resource_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), "RELEASE", request.idempotencyKey(), request.requestHash(), allocation.id(), timestamp(request.now()));
    }

    private List<LotRow> lockEligibleLots(UUID tenant, UUID workspace, List<BackingPosition> positions) {
        List<LotSelector> selectors = positions.stream().map(value -> new LotSelector(value.skuId(), value.warehouseId(), value.catalogItemId(), value.unit()))
                .distinct().sorted(Comparator.comparing((LotSelector value) -> value.skuId().toString()).thenComparing(value -> value.warehouseId().toString())).toList();
        if (selectors.isEmpty()) return List.of();
        String predicate = selectors.stream().map(value -> "(l.sku_id=? and l.warehouse_id=? and l.catalog_item_id=? and l.unit=?)").collect(Collectors.joining(" or "));
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        selectors.forEach(value -> { args.add(value.skuId()); args.add(value.warehouseId()); args.add(value.catalogItemId()); args.add(value.unit()); });
        return jdbc.query("select l.id,l.sku_id,l.catalog_item_id,l.warehouse_id,l.zone_id,l.unit,l.expiration_date,l.received_at,l.stock_quantity,l.reserved_quantity,l.version,coalesce((select p.quantity from warehouse.safety_stock_policy p where p.tenant_id=l.tenant_id and p.workspace_id=l.workspace_id and p.warehouse_id=l.warehouse_id and p.sku_id=l.sku_id),0) safety_stock "
                        + "from warehouse.inventory_lot l "
                        + "join catalog_management.sellable_sku sku on sku.tenant_id=l.tenant_id and sku.workspace_id=l.workspace_id and sku.id=l.sku_id "
                        + "join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "where l.tenant_id=? and l.workspace_id=? and sku.status='ACTIVE' and l.status='AVAILABLE' and l.expiration_date>current_date "
                        + "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' "
                        + "and coalesce(service.service_status,'OPERATIONAL')='OPERATIONAL' "
                        + "and (sku.temperature_min is null or (z.temperature_min is not null and z.temperature_min<=sku.temperature_min)) "
                        + "and (sku.temperature_max is null or (z.temperature_max is not null and z.temperature_max>=sku.temperature_max)) "
                        + "and ((sku.temperature_min is null and sku.temperature_max is null) or (l.temperature_value is not null and (sku.temperature_min is null or l.temperature_value>=sku.temperature_min) and (sku.temperature_max is null or l.temperature_value<=sku.temperature_max))) "
                        + "and not exists (select 1 from warehouse.inventory_temperature_evaluation evaluation where evaluation.tenant_id=l.tenant_id and evaluation.workspace_id=l.workspace_id and evaluation.lot_id=l.id and evaluation.status='OPEN' and evaluation.disposition='HOLD') "
                        + "and coalesce((select disposition.disposition from warehouse.inventory_lot_disposition disposition where disposition.tenant_id=l.tenant_id and disposition.workspace_id=l.workspace_id and disposition.lot_id=l.id order by disposition.created_at desc,disposition.id desc limit 1),'RELEASE') not in ('HOLD','WASTE','RETURN_TO_SUPPLIER') "
                        + "and l.stock_quantity-l.reserved_quantity>0 and (" + predicate + ") "
                        + "order by " + WarehouseLotLockOrder.inventoryLot("l") + " for update of l",
                (rs, row) -> new LotRow(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"), rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getString("unit"), rs.getObject("expiration_date", LocalDate.class), rs.getTimestamp("received_at").toInstant(), rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("reserved_quantity"), rs.getLong("version"), rs.getBigDecimal("safety_stock")), args.toArray());
    }

    private List<SelectedLot> selectFefo(List<PhysicalAllocationCommands.RequestedLine> requested, List<BackingPosition> positions, List<LotRow> lots) {
        Map<LineKey, BigDecimal> remaining = requested.stream().collect(Collectors.toMap(
                value -> new LineKey(value.skuId(), value.catalogItemId(), value.unit()),
                PhysicalAllocationCommands.RequestedLine::quantity, BigDecimal::add, LinkedHashMap::new));
        List<SelectedLot> result = new ArrayList<>();
        for (BackingPosition position : positions) {
            BigDecimal positionRemaining = position.quantity();
            LineKey key = new LineKey(position.skuId(), position.catalogItemId(), position.unit());
            BigDecimal demandRemaining = remaining.getOrDefault(key, BigDecimal.ZERO);
            if (demandRemaining.signum() <= 0) continue;
            for (LotRow lot : lots) {
                if (!lot.skuId().equals(position.skuId()) || !lot.warehouseId().equals(position.warehouseId())
                        || !lot.catalogItemId().equals(position.catalogItemId()) || !lot.unit().equals(position.unit())) continue;
                // Commercial backing already applied the warehouse safety-stock
                // policy. Applying it again here would make a backed quantity
                // appear unavailable and could create a false shortage.
                BigDecimal available = lot.stock().subtract(lot.reserved()).max(BigDecimal.ZERO);
                BigDecimal take = available.min(positionRemaining).min(demandRemaining);
                if (take.signum() <= 0) continue;
                result.add(new SelectedLot(lot.id(), lot.skuId(), lot.catalogItemId(), lot.warehouseId(), lot.zoneId(), lot.unit(), lot.expirationDate(), lot.stock(), lot.reserved(), lot.version(), lot.safetyStock(), take));
                positionRemaining = positionRemaining.subtract(take);
                demandRemaining = demandRemaining.subtract(take);
                remaining.put(key, demandRemaining);
                if (positionRemaining.signum() == 0 || demandRemaining.signum() == 0) break;
            }
        }
        return result;
    }

    private List<BackingPosition> backingPositions(UUID tenant, UUID workspace, UUID backingId) {
        return jdbc.query("select bl.sku_id,bl.catalog_item_id,bl.unit,p.warehouse_id,p.quantity from warehouse.inventory_backing_position p join warehouse.inventory_backing_line bl on bl.tenant_id=p.tenant_id and bl.workspace_id=p.workspace_id and bl.id=p.backing_line_id where p.tenant_id=? and p.workspace_id=? and bl.backing_id=? order by bl.sku_id,p.warehouse_id,p.id for update of p",
                (rs, row) -> new BackingPosition(rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"), rs.getString("unit"), rs.getObject("warehouse_id", UUID.class), rs.getBigDecimal("quantity")), tenant, workspace, backingId);
    }

    private void validateDemand(List<PhysicalAllocationCommands.RequestedLine> requested, List<BackingPosition> positions) {
        if (requested == null || requested.isEmpty() || positions == null || positions.isEmpty()) {
            throw error("PHYSICAL_ALLOCATION_EXCEEDS_BACKING", false);
        }
        Map<LineKey, BigDecimal> demand = new LinkedHashMap<>();
        for (PhysicalAllocationCommands.RequestedLine line : requested) {
            LineKey key = new LineKey(line.skuId(), line.catalogItemId(), line.unit());
            if (demand.put(key, line.quantity()) != null) throw error("FULFILLMENT_LINE_INVALID", false);
        }
        Map<LineKey, BigDecimal> backed = positions.stream().collect(Collectors.groupingBy(
                value -> new LineKey(value.skuId(), value.catalogItemId(), value.unit()),
                Collectors.mapping(BackingPosition::quantity, Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        if (!demand.keySet().equals(backed.keySet())) throw error("PHYSICAL_ALLOCATION_EXCEEDS_BACKING", false);
        for (Map.Entry<LineKey, BigDecimal> entry : demand.entrySet()) {
            if (entry.getValue().compareTo(backed.get(entry.getKey())) != 0) {
                throw error("PHYSICAL_ALLOCATION_EXCEEDS_BACKING", false);
            }
        }
    }

    private AllocationHeader lockByFulfillment(UUID tenant, UUID workspace, UUID fulfillmentId) {
        return jdbc.query("select id,status,version from warehouse.physical_allocation where tenant_id=? and workspace_id=? and fulfillment_id=? for update",
                (rs, row) -> new AllocationHeader(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version")), tenant, workspace, fulfillmentId).stream().findFirst().orElse(null);
    }

    private UUID allocationLineLot(UUID tenant, UUID workspace, UUID allocationId, UUID allocationLineId) {
        return jdbc.query("select lot_id from warehouse.physical_allocation_line where tenant_id=? and workspace_id=? and physical_allocation_id=? and id=?",
                (rs, row) -> rs.getObject("lot_id", UUID.class), tenant, workspace, allocationId, allocationLineId)
                .stream().findFirst().orElse(null);
    }

    /** Serializes the two-lot override pair before the row locks below. */
    private void lockOverrideLots(UUID tenant, UUID workspace, UUID currentLotId, UUID candidateLotId) {
        List<UUID> ids = java.util.stream.Stream.of(currentLotId, candidateLotId).filter(Objects::nonNull).distinct()
                .sorted().toList();
        if (ids.size() < 2) return;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|physical-allocation-fefo-override|" + ids.get(0) + "|" + ids.get(1));
    }

    private BackingRow lockBacking(UUID tenant, UUID workspace, UUID backingId) {
        return jdbc.query("select id,commercial_commitment_id,status from warehouse.inventory_backing where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new BackingRow(rs.getObject("id", UUID.class), rs.getObject("commercial_commitment_id", UUID.class), rs.getString("status")), tenant, workspace, backingId).stream().findFirst().orElse(null);
    }

    private List<AllocationLot> allocationLots(UUID tenant, UUID workspace, UUID allocationId) {
        return jdbc.query("select l.lot_id,l.sku_id,l.catalog_item_id,l.warehouse_id,l.zone_id,l.quantity,l.released_quantity,l.consumed_quantity,lot.expiration_date,lot.stock_quantity,lot.reserved_quantity,lot.unit,lot.version from warehouse.physical_allocation_line l join warehouse.inventory_lot lot on lot.tenant_id=l.tenant_id and lot.workspace_id=l.workspace_id and lot.id=l.lot_id where l.tenant_id=? and l.workspace_id=? and l.physical_allocation_id=? order by "
                        + WarehouseLotLockOrder.physicalAllocationLot("l", "lot") + " for update of lot",
                (rs, row) -> new AllocationLot(rs.getObject("lot_id", UUID.class), rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"), rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getBigDecimal("quantity"), rs.getBigDecimal("released_quantity"), rs.getBigDecimal("consumed_quantity"), rs.getObject("expiration_date", LocalDate.class), rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("reserved_quantity"), rs.getString("unit"), rs.getLong("version")), tenant, workspace, allocationId);
    }

    private AllocationResult load(UUID tenant, UUID workspace, UUID allocationId) {
        AllocationHeader header = jdbc.query("select id,inventory_backing_id,status,version from warehouse.physical_allocation where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new AllocationHeader(rs.getObject("id", UUID.class), rs.getString("status"), rs.getLong("version"), rs.getObject("inventory_backing_id", UUID.class)), tenant, workspace, allocationId).stream().findFirst().orElseThrow(() -> error("PHYSICAL_ALLOCATION_NOT_FOUND", true));
        List<PhysicalAllocationCommands.Line> lines = jdbc.query("select id,sku_id,catalog_item_id,warehouse_id,zone_id,lot_id,quantity,released_quantity,consumed_quantity,unit,expiration_date from warehouse.physical_allocation_line where tenant_id=? and workspace_id=? and physical_allocation_id=? order by sku_id,warehouse_id,expiration_date,lot_id",
                (rs, row) -> new PhysicalAllocationCommands.Line(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"), rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getObject("lot_id", UUID.class), rs.getBigDecimal("quantity"), rs.getBigDecimal("released_quantity"), rs.getBigDecimal("consumed_quantity"), rs.getString("unit"), rs.getObject("expiration_date", LocalDate.class)), tenant, workspace, allocationId);
        return new PhysicalAllocationCommands.AllocationResult(header.id(), header.backingId(), header.status(), lines, header.version());
    }

    private IdempotencyRow idempotency(UUID tenant, UUID workspace, UUID actor, String operation, String key) {
        return jdbc.query("select request_hash,resource_id from warehouse.physical_allocation_command_idempotency where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=? for update",
                (rs, row) -> new IdempotencyRow(rs.getString("request_hash"), rs.getObject("resource_id", UUID.class)), tenant, workspace, actor, operation, key).stream().findFirst().orElse(null);
    }

    private void lockCommand(UUID tenant, UUID workspace, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|physical-allocation|" + actor + "|" + operation + "|" + key);
    }

    private static void requireRequest(UUID tenant, UUID workspace, UUID fulfillmentId, UUID allocationLineId,
                                       UUID skuId, UUID lotId, UUID warehouseId, BigDecimal quantity, String unit,
                                       long expectedVersion, Instant now) {
        if (tenant == null || workspace == null || fulfillmentId == null || allocationLineId == null
                || skuId == null || lotId == null || warehouseId == null || quantity == null || quantity.signum() <= 0
                || unit == null || unit.isBlank() || expectedVersion < 0 || now == null) {
            throw new IllegalArgumentException("Picking scan validation request is incomplete");
        }
    }

    private static PickingScanValidationResult outcome(String outcome, PickingScanValidationRequest request) {
        return outcome(outcome, request, -1L);
    }

    private static PickingScanValidationResult outcome(String outcome, PickingScanValidationRequest request, long version) {
        return new PickingScanValidationResult(outcome, request.physicalAllocationLineId(), request.lotId(),
                request.warehouseId(), null, null, version);
    }

    private static PickingScanValidationResult result(String outcome, AllocationScanRow row,
                                                      BigDecimal remaining, long version) {
        return new PickingScanValidationResult(outcome, row.id(), row.lotId(), row.warehouseId(),
                row.quantity(), remaining, version);
    }

    private static void requireRequest(UUID tenant, UUID workspace, UUID actor, String key, String hash, Instant now) {
        if (tenant == null || workspace == null || actor == null || key == null || key.isBlank() || hash == null || !hash.matches("[0-9a-f]{64}") || now == null) throw new IllegalArgumentException("Physical allocation command is incomplete");
    }

    private static void ensureHash(String expected, String actual) { if (!Objects.equals(expected, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false); }
    private SellableSkuQuery.SellableSkuPolicy physicalSkuPolicy(UUID tenantId, UUID workspaceId, UUID skuId) {
        return sellableSkus == null ? null : sellableSkus.findPhysicalValidationPolicy(tenantId, workspaceId, skuId).orElse(null);
    }
    private static String normalizeOverrideReason(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= 2000 ? normalized : null;
    }
    private static boolean blockedDisposition(String value) {
        return "HOLD".equals(value) || "WASTE".equals(value) || "RETURN_TO_SUPPLIER".equals(value);
    }
    private static boolean temperatureCompatible(BigDecimal skuMin, BigDecimal skuMax,
                                                 BigDecimal zoneMin, BigDecimal zoneMax,
                                                 BigDecimal lotValue) {
        if (skuMin != null && (zoneMin == null || zoneMin.compareTo(skuMin) > 0)) return false;
        if (skuMax != null && (zoneMax == null || zoneMax.compareTo(skuMax) < 0)) return false;
        if (skuMin == null && skuMax == null) return true;
        return lotValue != null
                && (skuMin == null || lotValue.compareTo(skuMin) >= 0)
                && (skuMax == null || lotValue.compareTo(skuMax) <= 0);
    }
    private static String normalize(String value) { if (value == null || value.isBlank()) return "Physical allocation released"; return value.trim().length() > 2000 ? value.trim().substring(0, 2000) : value.trim(); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(Objects.requireNonNull(value, "now")); }
    private void trace(UUID tenantId, UUID workspaceId, UUID actorMembershipId, String eventType, UUID subjectId,
                       String occurrenceKey, Map<String, Object> metadata, Instant occurredAt) {
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(tenantId, workspaceId, actorMembershipId,
                "INVENTORY_AVAILABILITY", eventType, "PhysicalAllocation", subjectId, occurrenceKey,
                occurrenceKey, metadata, Objects.requireNonNull(occurredAt, "occurredAt")));
    }
    private static WarehouseOperationsService.WarehouseException error(String code, boolean notFound) { return new WarehouseOperationsService.WarehouseException(code, notFound); }

    private record BackingRow(UUID id, UUID commitmentId, String status) { }
    private record ExistingAllocation(UUID id, String status, long version) { }
    private record AllocationHeader(UUID id, String status, long version, UUID backingId) {
        private AllocationHeader(UUID id, String status, long version) { this(id, status, version, null); }
    }
    private record BackingPosition(UUID skuId, String catalogItemId, String unit, UUID warehouseId, BigDecimal quantity) { }
    private record LotSelector(UUID skuId, UUID warehouseId, String catalogItemId, String unit) { }
    private record LotRow(UUID id, UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId, String unit,
                          LocalDate expirationDate, Instant receivedAt, BigDecimal stock, BigDecimal reserved,
                          long version, BigDecimal safetyStock) { }
    private record SelectedLot(UUID lotId, UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId, String unit,
                               LocalDate expirationDate, BigDecimal stock, BigDecimal reserved, long version,
                               BigDecimal safetyStock, BigDecimal quantity) {
        private boolean matches(PhysicalAllocationCommands.RequestedLine line) {
            return skuId.equals(line.skuId()) && catalogItemId.equals(line.catalogItemId()) && unit.equals(line.unit());
        }
    }
    private record AllocationLot(UUID lotId, UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId,
                                 BigDecimal quantity, BigDecimal releasedQuantity, BigDecimal consumedQuantity,
                                 LocalDate expirationDate, BigDecimal stock, BigDecimal reserved, String unit,
                                 long version) {
        private BigDecimal remainingQuantity() {
            return quantity.subtract(releasedQuantity).subtract(consumedQuantity).max(BigDecimal.ZERO);
        }

        private boolean matches(LineKey key) {
            return skuId.equals(key.skuId()) && catalogItemId.equals(key.catalogItemId()) && unit.equals(key.unit());
        }
    }
    private record AllocationScanRow(UUID id, UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId, UUID lotId, BigDecimal quantity,
                                     BigDecimal releasedQuantity, BigDecimal consumedQuantity, String unit,
                                     String skuStatus, boolean skuVisible, String lotStatus, LocalDate expirationDate, String lotUnit,
                                     BigDecimal stockQuantity, BigDecimal reservedQuantity, long lotVersion,
                                     String warehouseStatus, String zoneStatus, String zoneType,
                                     BigDecimal temperatureValue, BigDecimal skuTemperatureMin, BigDecimal skuTemperatureMax,
                                     BigDecimal zoneTemperatureMin, BigDecimal zoneTemperatureMax, String warehouseServiceStatus,
                                     boolean temperatureHold, String latestDisposition) {
        private AllocationScanRow withSkuPolicy(SellableSkuQuery.SellableSkuPolicy policy) {
            return new AllocationScanRow(id, skuId, catalogItemId, warehouseId, zoneId, lotId, quantity,
                    releasedQuantity, consumedQuantity, unit, policy == null ? null : policy.status(),
                    policy != null && policy.visible(), lotStatus, expirationDate, lotUnit, stockQuantity,
                    reservedQuantity, lotVersion, warehouseStatus, zoneStatus, zoneType, temperatureValue,
                    policy == null ? null : policy.temperatureMin(), policy == null ? null : policy.temperatureMax(),
                    zoneTemperatureMin, zoneTemperatureMax, warehouseServiceStatus, temperatureHold, latestDisposition);
        }
    }
    private record CandidateLot(UUID id, UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId, String unit,
                                LocalDate expirationDate, BigDecimal stockQuantity, BigDecimal reservedQuantity, long version,
                                String status, String skuStatus, boolean skuVisible, String warehouseStatus,
                                String zoneStatus, String zoneType, BigDecimal temperatureValue,
                                BigDecimal skuTemperatureMin, BigDecimal skuTemperatureMax, BigDecimal zoneTemperatureMin,
                                BigDecimal zoneTemperatureMax, String warehouseServiceStatus, boolean temperatureHold,
                                String latestDisposition) {
        private CandidateLot withSkuPolicy(SellableSkuQuery.SellableSkuPolicy policy) {
            return new CandidateLot(id, skuId, catalogItemId, warehouseId, zoneId, unit, expirationDate,
                    stockQuantity, reservedQuantity, version, status, policy == null ? null : policy.status(),
                    policy != null && policy.visible(), warehouseStatus, zoneStatus, zoneType, temperatureValue,
                    policy == null ? null : policy.temperatureMin(), policy == null ? null : policy.temperatureMax(),
                    zoneTemperatureMin, zoneTemperatureMax, warehouseServiceStatus, temperatureHold, latestDisposition);
        }
    }
    private record IdempotencyRow(String requestHash, UUID resourceId) { }
    private record LineKey(UUID skuId, String catalogItemId, String unit) { }
}
