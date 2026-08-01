package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import com.nexa.api.warehouse.application.port.WarehouseOperationsPort;
import com.nexa.api.warehouse.domain.policy.FefoAllocationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nexa.api.warehouse.infrastructure.persistence.WarehousePersistenceSupport.*;

/**
 * PostgreSQL adapter for Warehouse commands and bounded read projections.
 * Application code reaches this adapter only through WarehouseOperationsPort.
 */
@Repository
@Profile("!test")
public class JdbcWarehouseOperationsAdapter implements WarehouseOperationsPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcWarehouseOperationsAdapter.class);
    private final JdbcTemplate jdbc;
    private final ChangeEventPersistencePort changeFeed;
    private final CatalogItemSnapshotLookupPort catalog;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcWarehouseOperationsAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.changeFeed = changeFeed;
        this.catalog = catalog;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public JdbcWarehouseOperationsAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog) {
        this.jdbc = jdbc;
        this.changeFeed = changeFeed;
        this.catalog = catalog;
        this.transactionTemplate = null;
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.WarehouseSummary> warehouses(
            CurrentAccessContext context, int page, int size, String sort) {
        requireRead(context);
        pageCheck(page, size);
        String order = sort(sort,
                Map.of("code", "code", "name", "name", "status", "status",
                        "createdAt", "created_at", "updatedAt", "updated_at"),
                "code");
        List<WarehouseOperationsService.WarehouseSummary> items = jdbc.query(
                "select id,code,name,address,status,version from warehouse.warehouse " +
                        "where tenant_id=? and workspace_id=? order by " + order + ",id asc limit ? offset ?",
                (rs, row) -> WarehousePersistenceSupport.warehouse(rs), tenant(context), workspace(context), size, page * size);
        long total = count("select count(*) from warehouse.warehouse where tenant_id=? and workspace_id=?",
                tenant(context), workspace(context));
        return new WarehouseOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.WarehouseSummary warehouse(CurrentAccessContext context, String id) {
        requireRead(context);
        return jdbc.query(
                        "select id,code,name,address,status,version from warehouse.warehouse " +
                                "where tenant_id=? and workspace_id=? and id=?",
                        (rs, row) -> WarehousePersistenceSupport.warehouse(rs), tenant(context), workspace(context), uuid(id))
                .stream().findFirst()
                .orElseThrow(() -> error("WAREHOUSE_NOT_FOUND", true));
    }

    @Override
    @Transactional
    public WarehouseOperationsService.WarehouseSummary createWarehouse(
            CurrentAccessContext context, String code, String name, String address) {
        requireWrite(context);
        String normalizedCode = boundedUpper(code, "code", 32);
        String normalizedName = bounded(name, "name", 160);
        String normalizedAddress = boundedNullable(address, "address", 2000);
        UUID id = UUID.randomUUID();
        Timestamp now = now();
        checkUpdated(jdbc.update(
                "insert into warehouse.warehouse(id,tenant_id,workspace_id,code,name,address,status,created_at,updated_at) " +
                        "values (?,?,?,?,?,?,'ACTIVE',?,?)",
                id, tenant(context), workspace(context), normalizedCode, normalizedName,
                normalizedAddress, now, now), "warehouse insert");
        appendEvent(context, id, "warehouse.warehouse.created", "warehouse");
        return warehouse(context, id.toString());
    }

    @Override
    @Transactional
    public WarehouseOperationsService.WarehouseSummary updateWarehouse(
            CurrentAccessContext context, String id, String name, String address,
            String status, long expected) {
        requireWrite(context);
        String normalizedStatus = status == null ? null : enumValue(status, "status", "ACTIVE", "SUSPENDED");
        String normalizedName = name == null ? null : bounded(name, "name", 160);
        String normalizedAddress = address == null ? null : boundedNullable(address, "address", 2000);
        checkUpdated(jdbc.update(
                "update warehouse.warehouse set name=coalesce(?,name),address=coalesce(?,address),status=coalesce(?,status)," +
                        "updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                normalizedName, normalizedAddress, normalizedStatus, now(),
                tenant(context), workspace(context), uuid(id), expected), "warehouse update", "CONCURRENCY_CONFLICT");
        appendEvent(context, uuid(id), "warehouse.warehouse.updated", "warehouse");
        return warehouse(context, id);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(
            CurrentAccessContext context, String warehouseId, int page, int size) {
        requireRead(context);
        pageCheck(page, size);
        UUID warehouseIdValue = uuid(warehouseId);
        if (!exists("select 1 from warehouse.warehouse where tenant_id=? and workspace_id=? and id=?",
                tenant(context), workspace(context), warehouseIdValue)) {
            throw error("WAREHOUSE_NOT_FOUND", true);
        }
        List<WarehouseOperationsService.ZoneSummary> items = jdbc.query(
                "select id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,version " +
                        "from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? " +
                        "order by code asc,id asc limit ? offset ?",
                (rs, row) -> WarehousePersistenceSupport.zone(rs), tenant(context), workspace(context), warehouseIdValue, size, page * size);
        long total = count("select count(*) from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=?",
                tenant(context), workspace(context), warehouseIdValue);
        return new WarehouseOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.ZoneSummary createZone(
            CurrentAccessContext context, String warehouseId, String code, String name,
            String type, BigDecimal min, BigDecimal max) {
        requireWrite(context);
        UUID warehouse = uuid(warehouseId);
        requireActiveWarehouse(context, warehouse);
        String normalizedType = enumValue(type, "type", "AMBIENT", "CHILLED", "FROZEN", "QUARANTINE");
        validateTemperatureRange(min, max);
        UUID id = UUID.randomUUID();
        Timestamp now = now();
        checkUpdated(jdbc.update(
                "insert into warehouse.storage_zone(id,tenant_id,workspace_id,warehouse_id,code,name,zone_type," +
                        "temperature_min,temperature_max,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                id, tenant(context), workspace(context), warehouse, boundedUpper(code, "code", 32),
                bounded(name, "name", 160), normalizedType, min, max, now, now), "zone insert");
        appendEvent(context, id, "warehouse.zone.created", "zone");
        return zone(context, warehouse, id);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.ZoneSummary updateZone(
            CurrentAccessContext context, String warehouseId, String zoneId, String name,
            BigDecimal min, BigDecimal max, String status, long expected) {
        requireWrite(context);
        UUID warehouse = uuid(warehouseId);
        UUID zone = uuid(zoneId);
        requireActiveWarehouse(context, warehouse);
        WarehouseOperationsService.ZoneSummary current = jdbc.query(
                        "select id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,version " +
                                "from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and id=?",
                        (rs, row) -> WarehousePersistenceSupport.zone(rs), tenant(context), workspace(context), warehouse, zone)
                .stream().findFirst().orElseThrow(() -> error("STORAGE_ZONE_NOT_FOUND", true));
        validateTemperatureRange(min == null ? current.temperatureMin() : min,
                max == null ? current.temperatureMax() : max);
        String normalizedStatus = status == null ? null : enumValue(status, "status", "ACTIVE", "SUSPENDED");
        checkUpdated(jdbc.update(
                "update warehouse.storage_zone set name=coalesce(?,name),temperature_min=coalesce(?,temperature_min)," +
                        "temperature_max=coalesce(?,temperature_max),status=coalesce(?,status),updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and warehouse_id=? and id=? and version=?",
                name == null ? null : bounded(name, "name", 160), min, max, normalizedStatus, now(),
                tenant(context), workspace(context), warehouse, zone, expected), "zone update", "CONCURRENCY_CONFLICT");
        appendEvent(context, zone, "warehouse.zone.updated", "zone");
        return zone(context, warehouse, zone);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> lots(
            CurrentAccessContext context, String catalogItemId, String warehouseId, String zoneId,
            String status, int page, int size, String sort) {
        requireRead(context);
        pageCheck(page, size);
        String order = sort(sort,
                Map.of("expirationDate", "expiration_date", "receivedAt", "received_at",
                        "batchNumber", "batch_number", "status", "status",
                        "quantityAvailable", "(stock_quantity-reserved_quantity)", "createdAt", "received_at"),
                "expirationDate");
        StringBuilder query = new StringBuilder(
                "select id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at," +
                        "stock_quantity,reserved_quantity,unit,status,version from warehouse.inventory_lot " +
                        "where tenant_id=? and workspace_id=?");
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (catalogItemId != null && !catalogItemId.isBlank()) {
            query.append(" and catalog_item_id=?");
            args.add(catalogItemId.trim());
        }
        if (warehouseId != null && !warehouseId.isBlank()) {
            query.append(" and warehouse_id=?");
            args.add(uuid(warehouseId));
        }
        if (zoneId != null && !zoneId.isBlank()) {
            query.append(" and zone_id=?");
            args.add(uuid(zoneId));
        }
        if (status != null && !status.isBlank()) {
            query.append(" and status=?");
            args.add(enumValue(status, "status", "AVAILABLE", "BLOCKED", "QUARANTINED", "EXPIRED", "DEPLETED"));
        }
        String countSql = query.toString().replace(
                "select id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,version",
                "select count(*)");
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        query.append(" order by ").append(order).append(",id asc limit ? offset ?");
        List<WarehouseOperationsService.LotSummary> items = jdbc.query(query.toString(),
                (rs, row) -> WarehousePersistenceSupport.lot(rs), pageArgs.toArray());
        long total = count(countSql, args.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.MovementSummary> movements(
            CurrentAccessContext context, String lotId, int page, int size, String sort) {
        requireRead(context);
        pageCheck(page, size);
        String order = sort(sort,
                Map.of("occurredAt", "occurred_at", "type", "movement_type", "catalogItemId", "catalog_item_id"),
                "occurredAt");
        String predicate = " where tenant_id=? and workspace_id=?";
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (lotId != null && !lotId.isBlank()) {
            predicate += " and lot_id=?";
            args.add(uuid(lotId));
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<WarehouseOperationsService.MovementSummary> items = jdbc.query(
                "select id,lot_id,catalog_item_id,movement_type,quantity,unit,quantity_before,quantity_after," +
                        "reserved_before,reserved_after,reason,occurred_at from warehouse.stock_movement" +
                        predicate + " order by " + order + ",id" + (order.endsWith(" desc") ? " desc" : " asc") + " limit ? offset ?",
                (rs, row) -> WarehousePersistenceSupport.movement(rs), pageArgs.toArray());
        long total = count("select count(*) from warehouse.stock_movement" + predicate, args.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.LotSummary lot(CurrentAccessContext context, String id) {
        requireRead(context);
        return loadLot(context, uuid(id), false);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.LotSummary receive(
            CurrentAccessContext context, WarehouseOperationsService.Receipt receipt,
            String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        lockIdempotency(context, "inbound", key);
        if (receipt == null) throw error("INVALID_REQUEST", false);
        String hash = requestHash("inbound", receipt);
        IdempotencyRecord prior = idempotent(context, "inbound", key);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return loadLot(context, uuid(prior.resourceId()), false);
        }
        UUID warehouse = uuidRequired(receipt.warehouseId(), "warehouseId");
        UUID zone = uuidRequired(receipt.zoneId(), "zoneId");
        String catalogItemId = bounded(receipt.catalogItemId(), "catalogItemId", 64);
        requireActiveWarehouse(context, warehouse);
        requireActiveZone(context, warehouse, zone);
        if (catalog.findActive(catalogItemId).isEmpty()) throw error("CATALOG_ITEM_NOT_FOUND", true);
        String unit = normalizedUnit(receipt.unit());
        String batch = bounded(receipt.batchNumber(), "batchNumber", 80);
        if (receipt.expirationDate() == null || !receipt.expirationDate().isAfter(LocalDate.now())) {
            throw error("INVALID_REQUEST", false);
        }
        if (receipt.quantity() == null || receipt.quantity().signum() <= 0) throw error("INVALID_REQUEST", false);
        validateTemperature(receipt.temperatureReading());
        String notes = boundedNullable(receipt.notes(), "notes", 2000);
        UUID id = UUID.randomUUID();
        Timestamp occurred = now();
        checkUpdated(jdbc.update(
                "insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,batch_number," +
                        "expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,temperature_value) " +
                        "values (?,?,?,?,?,?,?,?,?,?,0,?,'AVAILABLE',?,?)",
                id, tenant(context), workspace(context), warehouse, zone, catalogItemId, batch,
                receipt.expirationDate(), occurred, receipt.quantity(), unit,
                receipt.temperatureReading() == null ? null : receipt.temperatureReading().toPlainString(),
                receipt.temperatureReading()), "lot insert");
        insertMovement(context, warehouse, zone, id, catalogItemId, "INBOUND_RECEIPT",
                receipt.quantity(), unit, BigDecimal.ZERO, receipt.quantity(), BigDecimal.ZERO,
                receipt.quantity(), notes, correlation, occurred);
        appendEvent(context, id, "warehouse.lot.received", "lot", "ACTIVE", occurred);
        saveIdempotency(context, "inbound", key, hash, id.toString());
        return loadLot(context, id, false);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.LotSummary adjust(
            CurrentAccessContext context, String lotId, BigDecimal quantity, boolean inbound,
            String reason, long expected, String key, String correlation) {
        return mutateStock(context, lotId, quantity, inbound ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT",
                reason, expected, key, correlation);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.LotSummary waste(
            CurrentAccessContext context, String lotId, BigDecimal quantity, String reason,
            long expected, String key, String correlation) {
        return mutateStock(context, lotId, quantity, "WASTE", reason, expected, key, correlation);
    }

    private WarehouseOperationsService.LotSummary mutateStock(
            CurrentAccessContext context, String lotId, BigDecimal quantity, String movementType,
            String reason, long expected, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        String operation = movementType.toLowerCase(Locale.ROOT);
        lockIdempotency(context, operation, key);
        if (quantity == null || quantity.signum() <= 0) throw error("INVALID_REQUEST", false);
        String normalizedReason = bounded(reason, "reason", 2000);
        String hash = requestHash(operation, lotId, quantity, normalizedReason, expected);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return loadLot(context, uuid(prior.resourceId()), false);
        }
        UUID lotIdValue = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, lotIdValue, true);
        if (lot.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        if (movementType.equals("ADJUSTMENT_OUT") && !lot.status().equals("AVAILABLE")) {
            throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        }
        if (movementType.equals("WASTE") && lot.status().equals("EXPIRED")) {
            throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        }
        BigDecimal before = lot.onHand();
        BigDecimal after = movementType.equals("ADJUSTMENT_IN") ? before.add(quantity) : before.subtract(quantity);
        if (after.signum() < 0 || (!movementType.equals("ADJUSTMENT_IN") && lot.available().compareTo(quantity) < 0)) {
            throw error("INSUFFICIENT_AVAILABLE_STOCK", false);
        }
        String nextStatus = lot.status();
        if (lot.status().equals("AVAILABLE") && after.signum() == 0) nextStatus = "DEPLETED";
        checkUpdated(jdbc.update(
                "update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and version=?",
                after, nextStatus, tenant(context), workspace(context), lotIdValue, expected),
                "lot stock update", "CONCURRENCY_CONFLICT");
        insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), lotIdValue, lot.catalogItemId(),
                movementType, quantity, lot.unit(), before, after, lot.reserved(), lot.reserved(),
                normalizedReason, correlation, now());
        appendEvent(context, lotIdValue,
                movementType.equals("WASTE") ? "warehouse.lot.waste-recorded" : "warehouse.lot.adjusted",
                "lot");
        saveIdempotency(context, operation, key, hash, lotIdValue.toString());
        return loadLot(context, lotIdValue, false);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.LotSummary blockLot(
            CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        return transitionLot(context, lotId, "BLOCKED", "warehouse.lot.blocked", reason, expected, key, correlation);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.LotSummary quarantineLot(
            CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        return transitionLot(context, lotId, "QUARANTINED", "warehouse.lot.quarantined", reason, expected, key, correlation);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.LotSummary restoreLot(
            CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        return transitionLot(context, lotId, "AVAILABLE", "warehouse.lot.restored", reason, expected, key, correlation);
    }

    private WarehouseOperationsService.LotSummary transitionLot(
            CurrentAccessContext context, String lotId, String nextStatus, String eventType,
            String reason, long expected, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        String normalizedReason = bounded(reason, "reason", 2000);
        String hash = requestHash(eventType, lotId, expected, normalizedReason);
        lockIdempotency(context, eventType, key);
        IdempotencyRecord prior = idempotent(context, eventType, key);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return loadLot(context, uuid(prior.resourceId()), false);
        }
        UUID id = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, id, true);
        if (lot.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        if (nextStatus.equals("AVAILABLE")) {
            if (lot.expirationDate().isBefore(LocalDate.now()) || !lot.expirationDate().isAfter(LocalDate.now())) {
                throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
            }
            if (lot.onHand().signum() <= 0 || lot.status().equals("EXPIRED") || lot.status().equals("DEPLETED")) {
                throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
            }
            if (!lot.status().equals("BLOCKED") && !lot.status().equals("QUARANTINED")) {
                throw error("INVENTORY_RESERVATION_TRANSITION_INVALID", false);
            }
        } else if (lot.status().equals("EXPIRED") || lot.status().equals("DEPLETED")) {
            throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        }
        checkUpdated(jdbc.update(
                "update warehouse.inventory_lot set status=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                nextStatus, tenant(context), workspace(context), id, expected),
                "lot status update", "CONCURRENCY_CONFLICT");
        appendEvent(context, id, eventType, "lot");
        saveIdempotency(context, eventType, key, hash, id.toString());
        return loadLot(context, id, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseOperationsService.Availability> availability(
            CurrentAccessContext context, List<String> ids) {
        if (!context.allows(Permission.WAREHOUSE_READ) && !context.allows(Permission.CATALOG_READ)) {
            throw error("FORBIDDEN", false);
        }
        if (ids == null || ids.isEmpty() || ids.size() > MAX_PAGE_SIZE || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw error("INVALID_REQUEST", false);
        }
        List<String> normalized = ids.stream().map(id -> bounded(id, "catalogItemId", 64)).distinct().toList();
        String placeholders = normalized.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        args.addAll(normalized);
        Map<String, Integer> available = jdbc.query(
                "select l.catalog_item_id,count(*) from warehouse.inventory_lot l " +
                        "join warehouse.warehouse w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id " +
                        "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id " +
                        "where l.tenant_id=? and l.workspace_id=? and l.catalog_item_id in (" + placeholders + ") " +
                        "and l.status='AVAILABLE' and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity " +
                        "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' " +
                        "group by l.catalog_item_id",
                (rs, row) -> Map.entry(rs.getString(1), rs.getInt(2)), args.toArray())
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Instant asOf = Instant.now();
        return normalized.stream()
                .map(id -> new WarehouseOperationsService.Availability(id,
                        available.getOrDefault(id, 0) > 0 ? "AVAILABLE" : "UNAVAILABLE", asOf))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.ReservationPreview preview(CurrentAccessContext context, String orderId) {
        requireFulfillmentRead(context);
        OrderData order = loadOrder(context, uuid(orderId), false);
        List<WarehouseOperationsService.ProposalLine> proposals = lines(order.id()).stream()
                .map(line -> proposal(context, line, false)).toList();
        return new WarehouseOperationsService.ReservationPreview(order.id().toString(), order.number(),
                proposals, proposals.stream().allMatch(WarehouseOperationsService.ProposalLine::complete),
                Instant.now(), "Preview only — inventory is not reserved.");
    }

    @Override
    @Transactional
    public WarehouseOperationsService.ReservationDetail reserve(
            CurrentAccessContext context, String orderId, long expected, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        lockIdempotency(context, "reservation", key);
        String hash = requestHash("reservation", orderId, expected);
        IdempotencyRecord prior = idempotent(context, "reservation", key);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return loadReservation(context, uuid(prior.resourceId()), false);
        }
        OrderData order = loadOrder(context, uuid(orderId), true);
        IdempotencyRecord afterLock = idempotent(context, "reservation", key);
        if (afterLock != null) {
            requireSamePayload(afterLock, hash);
            return loadReservation(context, uuid(afterLock.resourceId()), false);
        }
        if (!order.status().equals("CONFIRMED")) throw error("FULFILLMENT_CANDIDATE_NOT_ELIGIBLE", false);
        if (order.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        if (count("select count(*) from warehouse.inventory_reservation where tenant_id=? and workspace_id=? " +
                        "and sales_order_id=? and status in ('PENDING','RESERVED')",
                tenant(context), workspace(context), order.id()) > 0) {
            throw error("INVENTORY_RESERVATION_ALREADY_EXISTS", false);
        }
        List<LineData> lines = lines(order.id());
        if (lines.isEmpty()) throw error("FULFILLMENT_CANDIDATE_NOT_ELIGIBLE", false);
        List<WarehouseOperationsService.ProposalLine> proposals = lines.stream()
                .map(line -> proposal(context, line, true)).toList();
        boolean complete = proposals.stream().allMatch(WarehouseOperationsService.ProposalLine::complete);
        UUID reservationId = UUID.randomUUID();
        Timestamp created = now();
        String status = complete ? "RESERVED" : "SHORTAGE";
        checkUpdated(jdbc.update(
                "insert into warehouse.inventory_reservation(id,tenant_id,workspace_id,sales_order_id,order_number," +
                        "client_account_id,status,created_at,updated_at,reserved_at,expires_at) values (?,?,?,?,?,?,?, ?,?,?,?)",
                reservationId, tenant(context), workspace(context), order.id(), order.number(),
                order.clientAccountId(), status, created, created, complete ? created : null,
                Timestamp.from(created.toInstant().plusSeconds(7200))), "reservation insert");
        for (WarehouseOperationsService.ProposalLine proposal : proposals) {
            UUID lineId = UUID.randomUUID();
            checkUpdated(jdbc.update(
                    "insert into warehouse.inventory_reservation_line(id,reservation_id,catalog_item_id,requested_quantity,unit,shortage_quantity) " +
                            "values (?,?,?,?,?,?)",
                    lineId, reservationId, proposal.catalogItemId(), proposal.requested(), proposal.unit(), proposal.shortage()),
                    "reservation line insert");
            if (!complete) {
                if (proposal.shortage().signum() > 0) {
                    checkUpdated(jdbc.update(
                            "insert into warehouse.reservation_shortage(id,reservation_line_id,quantity,reason) values (?,?,?,?)",
                            UUID.randomUUID(), lineId, proposal.shortage(), "Insufficient available stock"),
                            "reservation shortage insert");
                }
                continue;
            }
            for (WarehouseOperationsService.AllocationView allocation : proposal.allocations()) {
                WarehouseOperationsService.LotSummary lot = loadLot(context, uuid(allocation.lotId()), true);
                if (!lot.status().equals("AVAILABLE") || !lot.unit().equals(allocation.unit()) ||
                        lot.available().compareTo(allocation.quantity()) < 0) {
                    throw error("INVENTORY_SHORTAGE", false);
                }
                checkUpdated(jdbc.update(
                        "insert into warehouse.inventory_reservation_allocation(id,reservation_line_id,lot_id,quantity,unit,expiration_date) " +
                                "values (?,?,?,?,?,?)",
                        UUID.randomUUID(), lineId, uuid(allocation.lotId()), allocation.quantity(),
                        allocation.unit(), allocation.expirationDate()), "allocation insert");
                checkUpdated(jdbc.update(
                        "update warehouse.inventory_lot set reserved_quantity=reserved_quantity+?,version=version+1 " +
                                "where tenant_id=? and workspace_id=? and id=? and version=? and stock_quantity-reserved_quantity>=?",
                        allocation.quantity(), tenant(context), workspace(context), uuid(allocation.lotId()),
                        lot.version(), allocation.quantity()), "lot reservation update", "INVENTORY_SHORTAGE");
                insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), uuid(allocation.lotId()),
                        lot.catalogItemId(), "RESERVATION", allocation.quantity(), allocation.unit(),
                        lot.onHand(), lot.onHand(), lot.reserved(), lot.reserved().add(allocation.quantity()),
                        "Sales order " + order.number(), correlation, created);
            }
        }
        appendEvent(context, reservationId,
                complete ? "warehouse.reservation.created" : "warehouse.reservation.shortage", "reservation");
        saveIdempotency(context, "reservation", key, hash, reservationId.toString());
        return loadReservation(context, reservationId, false);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.ReservationDetail release(
            CurrentAccessContext context, String reservationId, long expected, String key,
            String reason, String correlation, boolean expiry) {
        requireWrite(context);
        requireIdempotency(key);
        String normalizedReason = bounded(reason, "reason", 2000);
        String operation = expiry ? "reservation-expiry" : "reservation-release";
        lockIdempotency(context, operation, key);
        String hash = requestHash(operation, reservationId, expected, normalizedReason);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return loadReservation(context, uuid(prior.resourceId()), false);
        }
        WarehouseOperationsService.ReservationDetail reservation = loadReservation(context, uuid(reservationId), true);
        if (!reservation.status().equals("RESERVED")) throw error("INVENTORY_RESERVATION_TRANSITION_INVALID", false);
        if (reservation.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        Timestamp occurred = now();
        for (WarehouseOperationsService.AllocationView allocation : allocations(reservation.id())) {
            WarehouseOperationsService.LotSummary lot = loadLot(context, uuid(allocation.lotId()), true);
            checkUpdated(jdbc.update(
                    "update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 " +
                            "where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                    allocation.quantity(), tenant(context), workspace(context), uuid(allocation.lotId()),
                    lot.version(), allocation.quantity()), "reservation release lot update", "CONCURRENCY_CONFLICT");
            insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), uuid(allocation.lotId()),
                    lot.catalogItemId(), expiry ? "RESERVATION_EXPIRATION" : "RESERVATION_RELEASE",
                    allocation.quantity(), allocation.unit(), lot.onHand(), lot.onHand(),
                    lot.reserved(), lot.reserved().subtract(allocation.quantity()),
                    normalizedReason, correlation, occurred);
        }
        String nextStatus = expiry ? "EXPIRED" : "RELEASED";
        checkUpdated(jdbc.update(
                "update warehouse.inventory_reservation set status=?,updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and version=? and status='RESERVED'",
                nextStatus, occurred, tenant(context), workspace(context), uuid(reservationId), expected),
                "reservation transition", "CONCURRENCY_CONFLICT");
        appendEvent(context, uuid(reservationId),
                expiry ? "warehouse.reservation.expired" : "warehouse.reservation.released", "reservation",
                "ACTIVE", occurred);
        saveIdempotency(context, operation, key, hash, reservationId);
        return loadReservation(context, uuid(reservationId), false);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.ReservationSummary> reservations(
            CurrentAccessContext context, String status, int page, int size) {
        requireRead(context);
        pageCheck(page, size);
        String predicate = "where tenant_id=? and workspace_id=?";
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (status != null && !status.isBlank()) {
            predicate += " and status=?";
            args.add(enumValue(status, "status", "PENDING", "RESERVED", "SHORTAGE", "RELEASED", "EXPIRED", "CANCELLED", "CONSUMED"));
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<WarehouseOperationsService.ReservationSummary> items = jdbc.query(
                "select id,sales_order_id,order_number,status,created_at,reserved_at,expires_at,version " +
                        "from warehouse.inventory_reservation " + predicate +
                        " order by created_at desc,id desc limit ? offset ?",
                (rs, row) -> new WarehouseOperationsService.ReservationSummary(
                        rs.getObject("id").toString(), rs.getObject("sales_order_id").toString(),
                        rs.getString("order_number"), rs.getString("status"), instant(rs, "created_at"),
                        instantNullable(rs, "reserved_at"), instant(rs, "expires_at"), rs.getLong("version")),
                pageArgs.toArray());
        long total = count("select count(*) from warehouse.inventory_reservation " + predicate, args.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.ReservationDetail reservation(CurrentAccessContext context, String id) {
        requireRead(context);
        return loadReservation(context, uuid(id), false);
    }

    @Override
    @Scheduled(fixedDelay = 60000L)
    public void expireReservations() {
        List<ScopeId> expired = jdbc.query(
                "select tenant_id,workspace_id,id from warehouse.inventory_reservation " +
                        "where status='RESERVED' and expires_at<current_timestamp " +
                        "order by expires_at,id limit 100 for update skip locked",
                (rs, row) -> new ScopeId(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)));
        for (ScopeId candidate : expired) {
            try {
                if (transactionTemplate == null) expireOne(candidate);
                else transactionTemplate.executeWithoutResult(status -> expireOne(candidate));
            } catch (RuntimeException exception) {
                LOGGER.error("Warehouse reservation expiration failed correlationId=reservation-expiry-{}", candidate.id(), exception);
            }
        }
    }

    private void expireOne(ScopeId candidate) {
        WarehouseOperationsService.ReservationDetail reservation = loadReservation(candidate.tenantId(), candidate.workspaceId(), candidate.id(), true);
        if (!reservation.status().equals("RESERVED")) return;
        Timestamp occurred = now();
        for (WarehouseOperationsService.AllocationView allocation : allocations(reservation.id())) {
            WarehouseOperationsService.LotSummary lot = loadLot(candidate.tenantId(), candidate.workspaceId(), uuid(allocation.lotId()), true);
            checkUpdated(jdbc.update(
                    "update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 " +
                            "where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                    allocation.quantity(), candidate.tenantId(), candidate.workspaceId(), uuid(allocation.lotId()),
                    lot.version(), allocation.quantity()), "expiry lot update", "CONCURRENCY_CONFLICT");
            insertMovement(candidate.tenantId(), candidate.workspaceId(), uuid(lot.warehouseId()), uuid(lot.zoneId()),
                    uuid(allocation.lotId()), lot.catalogItemId(), "RESERVATION_EXPIRATION", allocation.quantity(),
                    allocation.unit(), lot.onHand(), lot.onHand(), lot.reserved(),
                    lot.reserved().subtract(allocation.quantity()), "Reservation expired", null,
                    "reservation-expiry-" + candidate.id(), occurred);
        }
        checkUpdated(jdbc.update(
                "update warehouse.inventory_reservation set status='EXPIRED',updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                occurred, candidate.tenantId(), candidate.workspaceId(), candidate.id()),
                "expiry reservation transition", "CONCURRENCY_CONFLICT");
        appendEvent(candidate.tenantId(), candidate.workspaceId(), candidate.id(),
                "warehouse.reservation.expired", "reservation", "ACTIVE", occurred);
    }

    private WarehouseOperationsService.ProposalLine proposal(
            CurrentAccessContext context, LineData line, boolean lock) {
        String lockClause = lock ? " for update" : "";
        List<FefoAllocationPolicy.LotSnapshot> lots = jdbc.query(
                "select l.id,l.stock_quantity-l.reserved_quantity available,l.unit,l.expiration_date,l.received_at " +
                        "from warehouse.inventory_lot l " +
                        "join warehouse.warehouse w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id " +
                        "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id " +
                        "where l.tenant_id=? and l.workspace_id=? and l.catalog_item_id=? and l.status='AVAILABLE' " +
                        "and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity " +
                        "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' " +
                        "order by l.expiration_date,l.received_at,l.id" + lockClause,
                (rs, row) -> new FefoAllocationPolicy.LotSnapshot(
                        rs.getObject("id").toString(), rs.getBigDecimal("available"), rs.getString("unit"),
                        rs.getObject("expiration_date", LocalDate.class), instant(rs, "received_at")),
                tenant(context), workspace(context), line.catalogItemId());
        FefoAllocationPolicy.Result result = FefoAllocationPolicy.allocate(lots, line.quantity(), line.unit());
        return new WarehouseOperationsService.ProposalLine(line.catalogItemId(), line.quantity(), line.unit(),
                result.allocations().stream()
                        .map(item -> new WarehouseOperationsService.AllocationView(
                                item.lotId(), item.quantity(), item.unit(), item.expirationDate())).toList(),
                result.shortage(), result.complete());
    }

    private List<LineData> lines(UUID orderId) {
        // Every reservation transaction locks candidate lots in this deterministic
        // catalog-item order, avoiding cross-line lock inversion.
        return jdbc.query("select catalog_item_id,quantity,unit from sales.sales_order_line where sales_order_id=? order by catalog_item_id,id",
                (rs, row) -> new LineData(rs.getString("catalog_item_id"), rs.getBigDecimal("quantity"), rs.getString("unit")),
                orderId);
    }

    private OrderData loadOrder(CurrentAccessContext context, UUID id, boolean lock) {
        return jdbc.query(
                        "select id,number,status,version,client_account_id from sales.sales_order " +
                                "where tenant_id=? and workspace_id=? and id=?" + (lock ? " for update" : ""),
                        (rs, row) -> new OrderData(rs.getObject("id", UUID.class), rs.getString("number"),
                                rs.getString("status"), rs.getLong("version"),
                                rs.getObject("client_account_id", UUID.class)),
                        tenant(context), workspace(context), id)
                .stream().findFirst().orElseThrow(() -> error("FULFILLMENT_CANDIDATE_NOT_ELIGIBLE", true));
    }

    private WarehouseOperationsService.LotSummary loadLot(
            CurrentAccessContext context, UUID id, boolean lock) {
        return loadLot(tenant(context), workspace(context), id, lock);
    }

    private WarehouseOperationsService.LotSummary loadLot(
            UUID tenantId, UUID workspaceId, UUID id, boolean lock) {
        return jdbc.query(
                        "select id,warehouse_id,zone_id,catalog_item_id,batch_number,expiration_date,received_at," +
                                "stock_quantity,reserved_quantity,unit,status,version from warehouse.inventory_lot " +
                                "where tenant_id=? and workspace_id=? and id=?" + (lock ? " for update" : ""),
                        (rs, row) -> WarehousePersistenceSupport.lot(rs), tenantId, workspaceId, id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_LOT_NOT_FOUND", true));
    }

    private WarehouseOperationsService.ReservationDetail loadReservation(
            CurrentAccessContext context, UUID id, boolean lock) {
        return loadReservation(tenant(context), workspace(context), id, lock);
    }

    private WarehouseOperationsService.ReservationDetail loadReservation(
            UUID tenantId, UUID workspaceId, UUID id, boolean lock) {
        return jdbc.query(
                        "select id,sales_order_id,order_number,status,created_at,reserved_at,expires_at,version,client_account_id " +
                                "from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and id=?" +
                                (lock ? " for update" : ""),
                        (rs, row) -> new WarehouseOperationsService.ReservationDetail(
                                rs.getObject("id").toString(), rs.getObject("sales_order_id").toString(),
                                rs.getString("order_number"), rs.getString("status"), instant(rs, "created_at"),
                                instantNullable(rs, "reserved_at"), instant(rs, "expires_at"), rs.getLong("version"),
                                rs.getObject("client_account_id").toString(), allocations(rs.getObject("id", UUID.class))),
                        tenantId, workspaceId, id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_RESERVATION_NOT_FOUND", true));
    }

    private List<WarehouseOperationsService.AllocationView> allocations(String reservationId) {
        return allocations(uuid(reservationId));
    }

    private List<WarehouseOperationsService.AllocationView> allocations(UUID reservationId) {
        return jdbc.query(
                "select a.lot_id,a.quantity,a.unit,a.expiration_date " +
                        "from warehouse.inventory_reservation_allocation a " +
                        "join warehouse.inventory_reservation_line l on l.id=a.reservation_line_id " +
                        "where l.reservation_id=? order by a.lot_id asc",
                (rs, row) -> new WarehouseOperationsService.AllocationView(
                        rs.getObject("lot_id").toString(), rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getObject("expiration_date", LocalDate.class)), reservationId);
    }

    private WarehouseOperationsService.ZoneSummary zone(CurrentAccessContext context, UUID warehouseId, UUID id) {
        return jdbc.query(
                        "select id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,version " +
                                "from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and id=?",
                        (rs, row) -> WarehousePersistenceSupport.zone(rs), tenant(context), workspace(context), warehouseId, id)
                .stream().findFirst().orElseThrow(() -> error("STORAGE_ZONE_NOT_FOUND", true));
    }

    private void insertMovement(CurrentAccessContext context, UUID warehouseId, UUID zoneId, UUID lotId,
                                String catalogItemId, String movementType, BigDecimal quantity, String unit,
                                BigDecimal quantityBefore, BigDecimal quantityAfter,
                                BigDecimal reservedBefore, BigDecimal reservedAfter, String reason,
                                String correlation, Timestamp occurred) {
        insertMovement(tenant(context), workspace(context), warehouseId, zoneId, lotId, catalogItemId,
                movementType, quantity, unit, quantityBefore, quantityAfter, reservedBefore, reservedAfter,
                reason, context.membershipId().toString(), correlation, occurred);
    }

    private void insertMovement(UUID tenantId, UUID workspaceId, UUID warehouseId, UUID zoneId, UUID lotId,
                                String catalogItemId, String movementType, BigDecimal quantity, String unit,
                                BigDecimal quantityBefore, BigDecimal quantityAfter,
                                BigDecimal reservedBefore, BigDecimal reservedAfter, String reason,
                                String actorMembershipId, String correlation, Timestamp occurred) {
        checkUpdated(jdbc.update(
                "insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id," +
                        "movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason," +
                        "actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?, ?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, warehouseId, zoneId, lotId, catalogItemId, movementType,
                quantity, unit, quantityBefore, quantityAfter, reservedBefore, reservedAfter, reason,
                actorMembershipId == null ? null : uuid(actorMembershipId), correlation == null ? "unknown" : correlation, occurred), "movement insert");
    }

    private void appendEvent(CurrentAccessContext context, UUID aggregateId, String eventType, String aggregateType) {
        appendEvent(context, aggregateId, eventType, aggregateType, "ACTIVE", now());
    }

    private void appendEvent(CurrentAccessContext context, UUID aggregateId, String eventType, String aggregateType,
                             String publicStatus, Timestamp occurred) {
        appendEvent(tenant(context), workspace(context), aggregateId, eventType, aggregateType, publicStatus, occurred,
                uuid(context.membershipId().toString()), eventType);
    }

    private void appendEvent(UUID tenantId, UUID workspaceId, UUID aggregateId, String eventType, String aggregateType,
                             String publicStatus, Timestamp occurred) {
        appendEvent(tenantId, workspaceId, aggregateId, eventType, aggregateType, publicStatus, occurred, null,
                "reservation-expiry-" + aggregateId);
    }

    private void appendEvent(UUID tenantId, UUID workspaceId, UUID aggregateId, String eventType, String aggregateType,
                             String publicStatus, Timestamp occurred, UUID actorMembershipId, String correlationId) {
        changeFeed.append(tenantId.toString(), workspaceId.toString(), null, aggregateType,
                aggregateId.toString(), eventType, publicStatus, occurred.getTime(), false);
        checkUpdated(jdbc.update(
                "insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, aggregateId, eventType, occurred, actorMembershipId,
                correlationId), "inventory event insert");
    }

    private IdempotencyRecord idempotent(CurrentAccessContext context, String operation, String key) {
        return jdbc.query(
                        "select response_json,request_hash from warehouse.command_idempotency " +
                                "where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",
                        (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getString(2)),
                        tenant(context), workspace(context), operation, key)
                .stream().findFirst().map(record -> record).orElse(null);
    }

    private void lockIdempotency(CurrentAccessContext context, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant(context) + "|" + workspace(context) + "|" + operation + "|" + key);
    }

    private void saveIdempotency(CurrentAccessContext context, String operation, String key, String hash, String resourceId) {
        int inserted = jdbc.update(
                "insert into warehouse.command_idempotency(tenant_id,workspace_id,operation,idempotency_key,request_hash,response_json,created_at) " +
                        "values (?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,operation,idempotency_key) do nothing",
                tenant(context), workspace(context), operation, key, hash, resourceId, now());
        if (inserted == 0) {
            IdempotencyRecord prior = idempotent(context, operation, key);
            if (prior == null) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            requireSamePayload(prior, hash);
        } else if (inserted != 1) {
            throw error("INVALID_REQUEST", false);
        }
    }

    private void requireSamePayload(IdempotencyRecord record, String hash) {
        if (record.requestHash() != null && !record.requestHash().isBlank() && !record.requestHash().equalsIgnoreCase(hash)) {
            throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
        }
    }

    private static String requestHash(String operation, Object... values) {
        String canonical = operation + "|" + java.util.Arrays.stream(values)
                .map(value -> value == null ? "<null>" : String.valueOf(value).trim())
                .collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private void requireActiveWarehouse(CurrentAccessContext context, UUID id) {
        if (!exists("select 1 from warehouse.warehouse where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                tenant(context), workspace(context), id)) throw error("WAREHOUSE_NOT_FOUND", true);
    }

    private void requireActiveZone(CurrentAccessContext context, UUID warehouseId, UUID zoneId) {
        if (!exists("select 1 from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and id=? and status='ACTIVE'",
                tenant(context), workspace(context), warehouseId, zoneId)) throw error("STORAGE_ZONE_NOT_FOUND", true);
    }

    private void requireRead(CurrentAccessContext context) { context.requirePermission(Permission.WAREHOUSE_READ); }
    private void requireWrite(CurrentAccessContext context) { context.requirePermission(Permission.WAREHOUSE_WRITE); }
    private void requireFulfillmentRead(CurrentAccessContext context) { context.requirePermission(Permission.FULFILLMENT_READ); }
    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
    private boolean exists(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> true, args).stream().findFirst().orElse(false);
    }
    private record IdempotencyRecord(String resourceId, String requestHash) { }
    private record LineData(String catalogItemId, BigDecimal quantity, String unit) { }
    private record OrderData(UUID id, String number, String status, long version, UUID clientAccountId) { }
    private record ScopeId(UUID tenantId, UUID workspaceId, UUID id) { }
}
