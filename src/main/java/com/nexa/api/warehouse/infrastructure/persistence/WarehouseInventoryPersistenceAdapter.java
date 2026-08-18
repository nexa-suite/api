package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import com.nexa.api.warehouse.application.port.WarehouseInventoryPersistencePort;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLot;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLotStatus;
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

import static com.nexa.api.warehouse.infrastructure.persistence.WarehousePersistenceSupport.*;

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
            com.nexa.api.warehouse.application.port.WarehouseOperationalSettingsPort operationalSettings) {
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
        if (status != null && !status.isBlank()) { query.append(" and status=?"); args.add(enumValue(status, "status", "AVAILABLE", "BLOCKED", "QUARANTINED", "EXPIRED", "DEPLETED")); }
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
        InventoryLot lotAggregate = InventoryLot.rehydrate("new-lot", BigDecimal.ZERO, BigDecimal.ZERO, unit,
                InventoryLotStatus.AVAILABLE);
        lotAggregate.receive(receipt.quantity());
        UUID id = UUID.randomUUID();
        Timestamp occurred = now();
        checkUpdated(jdbc.update("insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,temperature_value) values (?,?,?,?,?,?,?,?,?,?,?,0,?,'AVAILABLE',?,?)",
                id, tenant(context), workspace(context), warehouse, zone, catalogItemId, sku.id(), batch, receipt.expirationDate(), occurred, receipt.quantity(), unit,
                receipt.temperatureReading() == null ? null : receipt.temperatureReading().toPlainString(), receipt.temperatureReading()), "lot insert");
        insertMovement(context, warehouse, zone, id, catalogItemId, sku.id(), "INBOUND_RECEIPT", receipt.quantity(), unit,
                BigDecimal.ZERO, receipt.quantity(), BigDecimal.ZERO, receipt.quantity(), notes, correlation, occurred);
        appendEvent(context, id, "warehouse.lot.received", "lot", "ACTIVE", occurred);
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
        if (!context.allows(com.nexa.api.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ)
                && !context.allows(com.nexa.api.tenantmanagement.domain.model.access.Permission.CATALOG_READ)) throw error("FORBIDDEN", false);
        if (ids == null || ids.isEmpty() || ids.size() > MAX_PAGE_SIZE || ids.stream().anyMatch(id -> id == null || id.isBlank())) throw error("INVALID_REQUEST", false);
        List<String> normalized = ids.stream().map(id -> bounded(id, "catalogItemId", 64)).distinct().toList();
        String placeholders = normalized.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context))); args.addAll(normalized);
        Map<String, Integer> available = jdbc.query("select l.catalog_item_id,count(*) from warehouse.inventory_lot l "
                        + "join warehouse.warehouse w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id "
                        + "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "where l.tenant_id=? and l.workspace_id=? and l.catalog_item_id in (" + placeholders + ") "
                        + "and l.status='AVAILABLE' and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity "
                        + "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' group by l.catalog_item_id",
                (rs, row) -> Map.entry(rs.getString(1), rs.getInt(2)), args.toArray()).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Instant asOf = Instant.now();
        return normalized.stream().map(id -> new WarehouseOperationsService.Availability(id, available.getOrDefault(id, 0) > 0 ? "AVAILABLE" : "UNAVAILABLE", asOf)).toList();
    }

    private void requireRead(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ); }
    private void requireWrite(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantmanagement.domain.model.access.Permission.WAREHOUSE_WRITE); }
}
