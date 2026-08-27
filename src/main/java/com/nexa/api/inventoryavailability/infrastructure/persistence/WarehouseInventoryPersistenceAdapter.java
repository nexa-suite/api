package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLot;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLotStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Cohesive JDBC adapter for receiving, lot lifecycle, movements and availability. */
@Repository
@Profile("!test")
public class WarehouseInventoryPersistenceAdapter extends WarehouseJdbcSupport
        implements WarehouseInventoryPersistencePort {

    @Autowired
    public WarehouseInventoryPersistenceAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort operationalSettings) {
        super(jdbc, changeFeed, catalog, transactionManager, operationalSettings);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> lots(
            CurrentAccessContext context, String catalogItemId, String warehouseId, String zoneId,
            String status, int page, int size, String sort) {
        requireRead(context);
        pageCheck(page, size);
        String order = sort(sort, Map.of("expirationDate", "expiration_date", "receivedAt", "received_at",
                "batchNumber", "batch_number", "status", "status", "quantityAvailable", "(stock_quantity-reserved_quantity)",
                "createdAt", "received_at"), "expirationDate");
        StringBuilder query = new StringBuilder("select id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
                + "stock_quantity,reserved_quantity,unit,status,version from warehouse.inventory_lot where tenant_id=? and workspace_id=?");
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (catalogItemId != null && !catalogItemId.isBlank()) { query.append(" and catalog_item_id=?"); args.add(catalogItemId.trim()); }
        if (warehouseId != null && !warehouseId.isBlank()) { query.append(" and warehouse_id=?"); args.add(uuid(warehouseId)); }
        if (zoneId != null && !zoneId.isBlank()) { query.append(" and zone_id=?"); args.add(uuid(zoneId)); }
        if (status != null && !status.isBlank()) { query.append(" and status=?"); args.add(enumValue(status, "status", "AVAILABLE", "BLOCKED", "QUARANTINED", "HOLD", "EXPIRED", "DEPLETED")); }
        String countSql = query.toString().replace("select id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,version", "select count(*)");
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add(page * size);
        query.append(" order by ").append(order).append(",id asc limit ? offset ?");
        List<WarehouseOperationsService.LotSummary> items = jdbc.query(query.toString(), (rs, row) -> WarehousePersistenceSupport.lot(rs), pageArgs.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size, count(countSql, args.toArray()));
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.MovementSummary> movements(
            CurrentAccessContext context, String lotId, int page, int size, String sort) {
        requireRead(context);
        pageCheck(page, size);
        String order = sort(sort, Map.of("occurredAt", "occurred_at", "type", "movement_type", "catalogItemId", "catalog_item_id"), "occurredAt");
        String predicate = " where tenant_id=? and workspace_id=?";
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (lotId != null && !lotId.isBlank()) { predicate += " and lot_id=?"; args.add(uuid(lotId)); }
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<WarehouseOperationsService.MovementSummary> items = jdbc.query(
                "select id,lot_id,catalog_item_id,sku_id,movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,occurred_at from warehouse.stock_movement"
                        + predicate + " order by " + order + ",id" + (order.endsWith(" desc") ? " desc" : " asc") + " limit ? offset ?",
                (rs, row) -> WarehousePersistenceSupport.movement(rs), pageArgs.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size, count("select count(*) from warehouse.stock_movement" + predicate, args.toArray()));
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.LotSummary lot(CurrentAccessContext context, String id) {
        requireRead(context);
        return loadLot(context, uuid(id), false);
    }

    public WarehouseOperationsService.LotSummary receive(CurrentAccessContext context, WarehouseOperationsService.Receipt receipt,
                                                          String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        lockIdempotency(context, "inbound", key);
        if (receipt == null) throw error("INVALID_REQUEST", false);
        String hash = requestHash("inbound", receipt);
        IdempotencyRecord prior = idempotent(context, "inbound", key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        UUID warehouse = uuidRequired(receipt.warehouseId(), "warehouseId");
        UUID zone = uuidRequired(receipt.zoneId(), "zoneId");
        String requestedCatalogItemId = receipt.catalogItemId() == null || receipt.catalogItemId().isBlank() ? null : bounded(receipt.catalogItemId(), "catalogItemId", 64);
        requireActiveWarehouse(context, warehouse);
        requireActiveZone(context, warehouse, zone);
        SkuReference sku = resolveSku(context, receipt.skuId(), requestedCatalogItemId);
        String catalogItemId = requestedCatalogItemId != null ? requestedCatalogItemId : sku.legacyCatalogItemId() == null || sku.legacyCatalogItemId().isBlank() ? sku.skuCode() : sku.legacyCatalogItemId();
        String unit = normalizedUnit(receipt.unit());
        String batch = bounded(receipt.batchNumber(), "batchNumber", 80);
        if (receipt.expirationDate() == null || !receipt.expirationDate().isAfter(LocalDate.now())) throw error("INVALID_REQUEST", false);
        if (receipt.quantity() == null || receipt.quantity().signum() <= 0) throw error("INVALID_REQUEST", false);
        validateTemperature(receipt.temperatureReading());
        String notes = boundedNullable(receipt.notes(), "notes", 2000);
        TemperatureRange skuRange = jdbc.query("select temperature_min,temperature_max from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and id=?",
                (rs, n) -> new TemperatureRange(rs.getBigDecimal(1), rs.getBigDecimal(2)), tenant(context), workspace(context), sku.id())
                .stream().findFirst().orElse(new TemperatureRange(null, null));
        if (skuRange.hasBounds() && receipt.temperatureReading() == null) throw error("TEMPERATURE_REQUIRED", false);
        TemperatureRange range = jdbc.query("select temperature_min,temperature_max from warehouse.storage_zone where tenant_id=? and workspace_id=? and id=?",
                (rs, n) -> new TemperatureRange(rs.getBigDecimal(1), rs.getBigDecimal(2)), tenant(context), workspace(context), zone)
                .stream().findFirst().orElse(new TemperatureRange(null, null));
        boolean temperatureExcursion = receipt.temperatureReading() != null
                && (!range.accepts(receipt.temperatureReading()) || !skuRange.accepts(receipt.temperatureReading()));
        InventoryLot lotAggregate = InventoryLot.rehydrate("new-lot", BigDecimal.ZERO, BigDecimal.ZERO, unit,
                InventoryLotStatus.AVAILABLE);
        lotAggregate.receive(receipt.quantity());
        if (temperatureExcursion) lotAggregate.markHold();
        UUID id = UUID.randomUUID();
        Timestamp occurred = now();
        checkUpdated(jdbc.update("insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,temperature_value) values (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                id, tenant(context), workspace(context), warehouse, zone, catalogItemId, sku.id(), batch, receipt.expirationDate(), occurred, receipt.quantity(), unit,
                temperatureExcursion ? "HOLD" : "AVAILABLE", range.snapshot(), receipt.temperatureReading()), "lot insert");
        if (temperatureExcursion) {
            jdbc.update("insert into warehouse.inventory_temperature_evaluation(id,tenant_id,workspace_id,lot_id,received_value,expected_min,expected_max,status,disposition,created_at) values (?,?,?,?,?,?,?,'OPEN','HOLD',?)",
                    UUID.randomUUID(), tenant(context), workspace(context), id, receipt.temperatureReading(), range.min(), range.max(), occurred);
        }
        insertMovement(context, warehouse, zone, id, catalogItemId, sku.id(), "INBOUND_RECEIPT", receipt.quantity(), unit,
                BigDecimal.ZERO, receipt.quantity(), BigDecimal.ZERO, receipt.quantity(), notes, correlation, occurred);
        appendEvent(context, id, temperatureExcursion ? "warehouse.lot.temperature-hold" : "warehouse.lot.received", "lot", temperatureExcursion ? "HOLD" : "ACTIVE", occurred);
        saveIdempotency(context, "inbound", key, hash, id.toString());
        return loadLot(context, id, false);
    }

    public WarehouseOperationsService.LotSummary adjust(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                         boolean inbound, String reason, long expected, String key, String correlation) {
        return mutateStock(context, lotId, quantity, inbound ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT", reason, expected, key, correlation);
    }

    public WarehouseOperationsService.LotSummary waste(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                        String reason, long expected, String key, String correlation) {
        return mutateStock(context, lotId, quantity, "WASTE", reason, expected, key, correlation);
    }

    private WarehouseOperationsService.LotSummary mutateStock(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                               String movementType, String reason, long expected, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        String operation = movementType.toLowerCase(java.util.Locale.ROOT);
        lockIdempotency(context, operation, key);
        if (quantity == null || quantity.signum() <= 0) throw error("INVALID_REQUEST", false);
        String normalizedReason = bounded(reason, "reason", 2000);
        String hash = requestHash(operation, lotId, quantity, normalizedReason, expected);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        UUID lotIdValue = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, lotIdValue, true);
        if (lot.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        if (movementType.equals("ADJUSTMENT_OUT") && !lot.status().equals("AVAILABLE")) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        if (movementType.equals("WASTE") && lot.status().equals("EXPIRED")) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        BigDecimal before = lot.onHand();
        InventoryLot lotAggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                InventoryLotStatus.valueOf(lot.status()));
        try {
            if (movementType.equals("ADJUSTMENT_IN")) lotAggregate.adjustIn(quantity);
            else if (movementType.equals("WASTE")) lotAggregate.recordWaste(quantity);
            else lotAggregate.adjustOut(quantity);
        } catch (IllegalStateException exception) {
            throw error(movementType.equals("ADJUSTMENT_IN") ? "INVENTORY_LOT_NOT_ALLOCATABLE" : "INSUFFICIENT_AVAILABLE_STOCK", false);
        }
        BigDecimal after = lotAggregate.onHand();
        String nextStatus = lotAggregate.status().name();
        checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                after, nextStatus, tenant(context), workspace(context), lotIdValue, expected), "lot stock update", "CONCURRENCY_CONFLICT");
        insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), lotIdValue, lot.catalogItemId(), uuidNullable(lot.skuId()), movementType,
                quantity, lot.unit(), before, after, lot.reserved(), lot.reserved(), normalizedReason, correlation, now());
        appendEvent(context, lotIdValue, movementType.equals("WASTE") ? "warehouse.lot.waste-recorded" : "warehouse.lot.adjusted", "lot");
        saveIdempotency(context, operation, key, hash, lotIdValue.toString());
        return loadLot(context, lotIdValue, false);
    }

    public WarehouseOperationsService.LotSummary blockLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        return transitionLot(context, lotId, "BLOCKED", "warehouse.lot.blocked", reason, expected, key, correlation);
    }

    public WarehouseOperationsService.LotSummary quarantineLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        return transitionLot(context, lotId, "QUARANTINED", "warehouse.lot.quarantined", reason, expected, key, correlation);
    }

    public WarehouseOperationsService.LotSummary restoreLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        return transitionLot(context, lotId, "AVAILABLE", "warehouse.lot.restored", reason, expected, key, correlation);
    }

    @Override
    public WarehouseOperationsService.LotSummary disposeLot(CurrentAccessContext context, String lotId, String disposition,
                                                             long expected, String reason, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        String normalized = enumValue(disposition, "disposition", "RELEASE", "HOLD", "WASTE", "RETURN_TO_SUPPLIER");
        String normalizedReason = bounded(reason, "reason", 2000);
        String operation = "lot-disposition-" + normalized.toLowerCase(java.util.Locale.ROOT);
        String hash = requestHash(operation, lotId, expected, normalized, normalizedReason);
        lockIdempotency(context, operation, key);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        UUID id = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, id, true);
        if (lot.version() != expected || lot.reserved().signum() > 0) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        String nextStatus = switch (normalized) {
            case "RELEASE" -> "AVAILABLE";
            case "HOLD" -> "HOLD";
            case "WASTE" -> "DEPLETED";
            case "RETURN_TO_SUPPLIER" -> "BLOCKED";
            default -> throw error("INVALID_REQUEST", false);
        };
        BigDecimal nextStock = "WASTE".equals(normalized) || "RETURN_TO_SUPPLIER".equals(normalized) ? BigDecimal.ZERO : lot.onHand();
        checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                nextStock, nextStatus, tenant(context), workspace(context), id, expected), "lot disposition", "CONCURRENCY_CONFLICT");
        jdbc.update("insert into warehouse.inventory_lot_disposition(id,tenant_id,workspace_id,lot_id,disposition,reason,actor_membership_id,created_at) values (?,?,?,?,?,?,?,current_timestamp)",
                UUID.randomUUID(), tenant(context), workspace(context), id, normalized, normalizedReason, context.membershipId().value());
        if ("WASTE".equals(normalized) || "RETURN_TO_SUPPLIER".equals(normalized)) {
            insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), id, lot.catalogItemId(), uuidNullable(lot.skuId()),
                    "WASTE", lot.onHand(), lot.unit(), lot.onHand(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, normalizedReason, correlation, now());
        }
        jdbc.update("update warehouse.inventory_temperature_evaluation set status='RESOLVED',disposition=?,resolution_reason=?,resolved_at=current_timestamp where tenant_id=? and workspace_id=? and lot_id=? and status='OPEN'",
                normalized, normalizedReason, tenant(context), workspace(context), id);
        appendEvent(context, id, "warehouse.lot.disposition-recorded", "lot", nextStatus, now());
        saveIdempotency(context, operation, key, hash, id.toString());
        return loadLot(context, id, false);
    }

    private WarehouseOperationsService.LotSummary transitionLot(CurrentAccessContext context, String lotId, String nextStatus,
                                                                  String eventType, String reason, long expected, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        String normalizedReason = bounded(reason, "reason", 2000);
        String hash = requestHash(eventType, lotId, expected, normalizedReason);
        lockIdempotency(context, eventType, key);
        IdempotencyRecord prior = idempotent(context, eventType, key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        UUID id = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, id, true);
        if (lot.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        InventoryLot lotAggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                InventoryLotStatus.valueOf(lot.status()));
        try {
            if (nextStatus.equals("BLOCKED")) lotAggregate.markBlocked();
            else if (nextStatus.equals("QUARANTINED")) lotAggregate.markQuarantined();
            else if (nextStatus.equals("AVAILABLE")) lotAggregate.restoreAvailability();
            else if (nextStatus.equals("HOLD")) lotAggregate.markHold();
            else throw error("INVENTORY_RESERVATION_TRANSITION_INVALID", false);
        } catch (IllegalStateException exception) {
            throw error("INVENTORY_RESERVATION_TRANSITION_INVALID", false);
        }
        checkUpdated(jdbc.update("update warehouse.inventory_lot set status=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                nextStatus, tenant(context), workspace(context), id, expected), "lot status update", "CONCURRENCY_CONFLICT");
        appendEvent(context, id, eventType, "lot");
        saveIdempotency(context, eventType, key, hash, id.toString());
        return loadLot(context, id, false);
    }

    @Transactional(readOnly = true)
    public List<WarehouseOperationsService.Availability> availability(CurrentAccessContext context, List<String> ids) {
        if (!context.allows(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ)
                && !context.allows(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.CATALOG_READ)) throw error("FORBIDDEN", false);
        if (ids == null || ids.isEmpty() || ids.size() > MAX_PAGE_SIZE || ids.stream().anyMatch(id -> id == null || id.isBlank())) throw error("INVALID_REQUEST", false);
        List<String> normalized = ids.stream().map(id -> bounded(id, "catalogItemId", 64)).distinct().toList();
        String placeholders = normalized.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context))); args.addAll(normalized);
        List<AvailabilityQuantities> rows = jdbc.query("with active_backing as ("
                        + "select line.tenant_id,line.workspace_id,line.catalog_item_id,position.warehouse_id,coalesce(sum(position.quantity),0) active_quantity "
                        + "from warehouse.inventory_backing_position position "
                        + "join warehouse.inventory_backing_line line on line.tenant_id=position.tenant_id and line.workspace_id=position.workspace_id and line.id=position.backing_line_id "
                        + "join warehouse.inventory_backing backing on backing.tenant_id=line.tenant_id and backing.workspace_id=line.workspace_id and backing.id=line.backing_id "
                        + "where backing.status='BACKED' group by line.tenant_id,line.workspace_id,line.catalog_item_id,position.warehouse_id) "
                        + "select l.catalog_item_id,l.warehouse_id,"
                        + "coalesce(sum(l.stock_quantity),0) physical_quantity,"
                        + "coalesce(sum(case when l.status='AVAILABLE' and l.expiration_date>current_date "
                        + "and l.stock_quantity>l.reserved_quantity and w.status='ACTIVE' and z.status='ACTIVE' "
                        + "and z.zone_type<>'QUARANTINE' and coalesce(service.service_status,'OPERATIONAL')='OPERATIONAL' "
                        + "and sku.status='ACTIVE' "
                        + "and (sku.temperature_min is null or (z.temperature_min is not null and z.temperature_min<=sku.temperature_min)) "
                        + "and (sku.temperature_max is null or (z.temperature_max is not null and z.temperature_max>=sku.temperature_max)) "
                        + "and ((sku.temperature_min is null and sku.temperature_max is null) or (l.temperature_value is not null and (sku.temperature_min is null or l.temperature_value>=sku.temperature_min) and (sku.temperature_max is null or l.temperature_value<=sku.temperature_max))) "
                        + "and not exists (select 1 from warehouse.inventory_temperature_evaluation evaluation where evaluation.tenant_id=l.tenant_id and evaluation.workspace_id=l.workspace_id and evaluation.lot_id=l.id and evaluation.status='OPEN' and evaluation.disposition='HOLD') "
                        + "and coalesce((select disposition.disposition from warehouse.inventory_lot_disposition disposition where disposition.tenant_id=l.tenant_id and disposition.workspace_id=l.workspace_id and disposition.lot_id=l.id order by disposition.created_at desc,disposition.id desc limit 1),'RELEASE') not in ('HOLD','WASTE','RETURN_TO_SUPPLIER') "
                        + "then l.stock_quantity-l.reserved_quantity else 0 end),0) eligible_quantity,"
                        + "coalesce(max(ss.quantity),0) safety_stock,coalesce(max(active_backing.active_quantity),0) active_backing_quantity "
                        + "from warehouse.inventory_lot l "
                        + "join warehouse.warehouse w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id "
                        + "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "join catalog_management.sellable_sku sku on sku.id=l.sku_id and sku.tenant_id=l.tenant_id and sku.workspace_id=l.workspace_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "left join warehouse.safety_stock_policy ss on ss.tenant_id=l.tenant_id and ss.workspace_id=l.workspace_id "
                        + "and ss.warehouse_id=l.warehouse_id and ss.sku_id=l.sku_id "
                        + "left join active_backing on active_backing.tenant_id=l.tenant_id and active_backing.workspace_id=l.workspace_id "
                        + "and active_backing.catalog_item_id=l.catalog_item_id and active_backing.warehouse_id=l.warehouse_id "
                        + "where l.tenant_id=? and l.workspace_id=? and l.catalog_item_id in (" + placeholders + ") "
                        + "group by l.catalog_item_id,l.warehouse_id",
                (rs, row) -> new AvailabilityQuantities(rs.getString("catalog_item_id"),
                        rs.getBigDecimal("physical_quantity"), rs.getBigDecimal("eligible_quantity"),
                        rs.getBigDecimal("safety_stock"), rs.getBigDecimal("active_backing_quantity")), args.toArray());
        Map<String, BigDecimal> physical = new java.util.HashMap<>();
        Map<String, BigDecimal> safety = new java.util.HashMap<>();
        Map<String, BigDecimal> sellable = new java.util.HashMap<>();
        for (AvailabilityQuantities row : rows) {
            physical.merge(row.catalogItemId(), row.physicalQuantity(), BigDecimal::add);
            safety.merge(row.catalogItemId(), row.safetyStock(), BigDecimal::add);
            sellable.merge(row.catalogItemId(), row.eligibleQuantity().subtract(row.safetyStock()).subtract(row.activeBackingQuantity()).max(BigDecimal.ZERO), BigDecimal::add);
        }
        Instant asOf = Instant.now();
        return normalized.stream().map(id -> new WarehouseOperationsService.Availability(id,
                sellable.getOrDefault(id, BigDecimal.ZERO).signum() > 0 ? "AVAILABLE" : "UNAVAILABLE", asOf,
                physical.getOrDefault(id, BigDecimal.ZERO), safety.getOrDefault(id, BigDecimal.ZERO),
                sellable.getOrDefault(id, BigDecimal.ZERO))).toList();
    }

    private void requireRead(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ); }
    private void requireWrite(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_WRITE); }

    private record TemperatureRange(BigDecimal min, BigDecimal max) {
        String snapshot() { return (min == null ? "" : min) + ":" + (max == null ? "" : max); }
        boolean hasBounds() { return min != null || max != null; }
        boolean accepts(BigDecimal value) {
            return value != null && (min == null || value.compareTo(min) >= 0)
                    && (max == null || value.compareTo(max) <= 0);
        }
    }

    private record AvailabilityQuantities(String catalogItemId, BigDecimal physicalQuantity,
                                          BigDecimal eligibleQuantity, BigDecimal safetyStock,
                                          BigDecimal activeBackingQuantity) { }
}
