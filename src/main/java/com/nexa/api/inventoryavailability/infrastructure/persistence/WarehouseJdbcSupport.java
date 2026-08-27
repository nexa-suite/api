package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLotStatus;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseBuyerProjection;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseHours;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseInternalSnapshot;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseLocation;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseOperationalProfile;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseProfile;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseSelectionPolicy;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseServiceability;
import com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseStatus;
import com.nexa.api.inventoryavailability.domain.policy.FefoAllocationPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/**
 * Shared JDBC primitives for the Warehouse vertical adapters.
 *
 * This type deliberately contains only tenant-scoped queries, row mapping,
 * idempotency and event persistence primitives. Workflow authority stays in
 * the focused configuration, inventory and reservation adapters.
 */
abstract class WarehouseJdbcSupport {
    protected final JdbcTemplate jdbc;
    protected final ChangeEventPersistencePort changeFeed;
    protected final CatalogItemSnapshotLookupPort catalog;
    protected final TransactionTemplate transactionTemplate;
    protected final WarehouseOperationalSettingsPort operationalSettings;

    protected WarehouseJdbcSupport(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            WarehouseOperationalSettingsPort operationalSettings) {
        this.jdbc = jdbc;
        this.changeFeed = changeFeed;
        this.catalog = catalog;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.operationalSettings = operationalSettings;
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    protected boolean exists(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> true, args).stream().findFirst().orElse(false);
    }

    protected void requireActiveWarehouse(CurrentAccessContext context, UUID id) {
        if (!exists("select 1 from warehouse.warehouse where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                tenant(context), workspace(context), id)) throw error("WAREHOUSE_NOT_FOUND", true);
    }

    protected void requireActiveZone(CurrentAccessContext context, UUID warehouseId, UUID zoneId) {
        if (!exists("select 1 from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and id=? and status='ACTIVE'",
                tenant(context), workspace(context), warehouseId, zoneId)) throw error("STORAGE_ZONE_NOT_FOUND", true);
    }

    protected WarehouseOperationsService.WarehouseSummary warehouseForUpdate(CurrentAccessContext context, UUID id) {
        return jdbc.query(
                        "select id,code,name,address,status,version from warehouse.warehouse "
                                + "where tenant_id=? and workspace_id=? and id=? for update",
                        (rs, row) -> warehouse(rs), tenant(context), workspace(context), id)
                .stream().findFirst().orElseThrow(() -> error("WAREHOUSE_NOT_FOUND", true));
    }

    protected WarehouseOperationalSettingsPort.Snapshot settings(CurrentAccessContext context) {
        if (operationalSettings == null) {
            return new WarehouseOperationalSettingsPort.Snapshot("MANUAL", "WORKSPACE_HOURS", "STANDARD",
                    "COARSE", "AVAILABLE_ONLY", LocalTime.of(8, 0), LocalTime.of(18, 0), 120, true, 0);
        }
        return operationalSettings.find(tenant(context).toString(), workspace(context).toString())
                .orElseThrow(() -> error("OPERATIONAL_SETTINGS_NOT_FOUND", true));
    }

    protected WarehouseOperationsService.OperationalProfile operationalProfile(
            WarehouseOperationsService.WarehouseSummary summary,
            WarehouseOperationalSettingsPort.Snapshot settings, Coordinates coordinates) {
        WarehouseOperationalProfile profile = new WarehouseOperationalProfile(
                new WarehouseProfile(uuid(summary.id(), false), summary.code(), summary.name(),
                        new WarehouseLocation(summary.address()), WarehouseStatus.valueOf(summary.status()), summary.version()),
                new WarehouseHours(settings.startsAt(), settings.endsAt()),
                new WarehouseServiceability("ACTIVE".equals(summary.status())),
                WarehouseSelectionPolicy.valueOf(settings.selectionPolicy()), settings.version());
        WarehouseInternalSnapshot snapshot = profile.internalSnapshot();
        return new WarehouseOperationsService.OperationalProfile(snapshot.warehouseId().toString(), snapshot.code(),
                snapshot.name(), snapshot.address(), snapshot.status().name(), snapshot.hours().startsAt(),
                snapshot.hours().endsAt(), snapshot.serviceability().serviceable(), snapshot.selectionPolicy().name(),
                snapshot.warehouseVersion(), snapshot.settingsVersion(), coordinates.latitude(), coordinates.longitude());
    }

    protected WarehouseOperationsService.BuyerWarehouse buyerProjection(
            CurrentAccessContext context, WarehouseOperationsService.WarehouseSummary summary,
            WarehouseOperationalSettingsPort.Snapshot settings) {
        WarehouseOperationalProfile profile = new WarehouseOperationalProfile(
                new WarehouseProfile(uuid(summary.id(), false), summary.code(), summary.name(),
                        new WarehouseLocation(summary.address()), WarehouseStatus.valueOf(summary.status()), summary.version()),
                new WarehouseHours(settings.startsAt(), settings.endsAt()),
                new WarehouseServiceability("ACTIVE".equals(summary.status())),
                WarehouseSelectionPolicy.valueOf(settings.selectionPolicy()), settings.version());
        WarehouseBuyerProjection projection = profile.buyerProjection();
        Coordinates coordinates = coordinates(context, uuid(summary.id(), false));
        return new WarehouseOperationsService.BuyerWarehouse(projection.warehouseId().toString(), projection.code(),
                projection.name(), projection.address(), projection.hours().startsAt(), projection.hours().endsAt(),
                projection.serviceable(), projection.version(), coordinates.latitude(), coordinates.longitude());
    }

    protected Coordinates coordinates(CurrentAccessContext context, UUID warehouseId) {
        return jdbc.query("select latitude,longitude from warehouse.warehouse_service_configuration "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=?",
                rs -> rs.next() ? new Coordinates(rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"))
                        : new Coordinates(null, null), tenant(context), workspace(context), warehouseId);
    }

    protected void upsertCoordinates(CurrentAccessContext context, UUID warehouseId, BigDecimal latitude,
                                     BigDecimal longitude, Timestamp changedAt) {
        jdbc.update("insert into warehouse.warehouse_service_configuration "
                        + "(warehouse_id,tenant_id,workspace_id,latitude,longitude,updated_at) values (?,?,?,?,?,?) "
                        + "on conflict (warehouse_id) do update set latitude=excluded.latitude,longitude=excluded.longitude,"
                        + "version=warehouse.warehouse_service_configuration.version+1,updated_at=excluded.updated_at",
                warehouseId, tenant(context), workspace(context), latitude, longitude, changedAt);
    }

    protected WarehouseOperationsService.LotSummary loadLot(CurrentAccessContext context, UUID id, boolean lock) {
        return loadLot(tenant(context), workspace(context), id, lock);
    }

    protected WarehouseOperationsService.LotSummary loadLot(UUID tenantId, UUID workspaceId, UUID id, boolean lock) {
        return jdbc.query(
                        "select id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
                                + "stock_quantity,reserved_quantity,unit,status,version from warehouse.inventory_lot "
                                + "where tenant_id=? and workspace_id=? and id=?" + (lock ? " for update" : ""),
                        (rs, row) -> lot(rs), tenantId, workspaceId, id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_LOT_NOT_FOUND", true));
    }

    protected WarehouseOperationsService.ReservationDetail loadReservation(CurrentAccessContext context, UUID id, boolean lock) {
        return loadReservation(tenant(context), workspace(context), id, lock);
    }

    protected WarehouseOperationsService.ReservationDetail loadReservation(UUID tenantId, UUID workspaceId, UUID id, boolean lock) {
        return jdbc.query(
                        "select id,sales_order_id,order_number,status,created_at,reserved_at,expires_at,version,client_account_id "
                                + "from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and id=?"
                                + (lock ? " for update" : ""),
                        (rs, row) -> new WarehouseOperationsService.ReservationDetail(
                                rs.getObject("id").toString(), rs.getObject("sales_order_id").toString(),
                                rs.getString("order_number"), rs.getString("status"), instant(rs, "created_at"),
                                instantNullable(rs, "reserved_at"), instant(rs, "expires_at"), rs.getLong("version"),
                                rs.getObject("client_account_id").toString(), allocations(tenantId, workspaceId, rs.getObject("id", UUID.class))),
                tenantId, workspaceId, id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_RESERVATION_NOT_FOUND", true));
    }

    protected List<WarehouseOperationsService.AllocationView> allocations(UUID tenantId, UUID workspaceId, UUID reservationId) {
        return jdbc.query(
                "select a.lot_id,a.quantity,a.unit,a.expiration_date "
                        + "from warehouse.inventory_reservation_allocation a "
                        + "join warehouse.inventory_reservation_line l on l.id=a.reservation_line_id "
                        + "join warehouse.inventory_reservation r on r.id=l.reservation_id "
                        + "join warehouse.inventory_lot lot on lot.id=a.lot_id "
                        + "and lot.tenant_id=r.tenant_id and lot.workspace_id=r.workspace_id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? order by a.lot_id asc",
                (rs, row) -> new WarehouseOperationsService.AllocationView(
                        rs.getObject("lot_id").toString(), rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getObject("expiration_date", LocalDate.class)), tenantId, workspaceId, reservationId);
    }

    protected List<LineData> lines(CurrentAccessContext context, UUID orderId) {
        return jdbc.query("select line.sku_id,line.catalog_item_id,line.quantity,upper(line.unit) as unit from sales.sales_order_line line "
                        + "join sales.sales_order order_header on order_header.id=line.sales_order_id "
                        + "where order_header.tenant_id=? and order_header.workspace_id=? and line.sales_order_id=? "
                        + "order by coalesce(line.sku_id::text,line.catalog_item_id),line.id",
                (rs, row) -> new LineData(rs.getObject("sku_id", UUID.class), rs.getString("catalog_item_id"),
                        rs.getBigDecimal("quantity"), rs.getString("unit")),
                tenant(context), workspace(context), orderId);
    }

    protected OrderData loadOrder(CurrentAccessContext context, UUID id, boolean lock) {
        return jdbc.query(
                        "select id,number,status,version,client_account_id,delivery_snapshot from sales.sales_order "
                                + "where tenant_id=? and workspace_id=? and id=?" + (lock ? " for update" : ""),
                        (rs, row) -> new OrderData(rs.getObject("id", UUID.class), rs.getString("number"),
                                rs.getString("status"), rs.getLong("version"), rs.getObject("client_account_id", UUID.class),
                                rs.getString("delivery_snapshot")),
                        tenant(context), workspace(context), id)
                .stream().findFirst().orElseThrow(() -> error("FULFILLMENT_CANDIDATE_NOT_ELIGIBLE", true));
    }

    protected WarehouseOperationsService.ProposalLine proposal(CurrentAccessContext context, LineData line, boolean lock,
                                                               UUID selectedWarehouseId) {
        SkuReference resolved = line.skuId() == null ? resolveSku(context, null, line.catalogItemId()) : null;
        UUID effectiveSkuId = line.skuId() == null ? resolved.id() : line.skuId();
        String skuPredicate = effectiveSkuId == null ? "l.catalog_item_id=?" : "l.sku_id=?";
        Object skuArgument = effectiveSkuId == null ? line.catalogItemId() : effectiveSkuId;
        lockSkuScope(context, effectiveSkuId == null ? "legacy:" + line.catalogItemId() : effectiveSkuId.toString());
        String warehousePredicate = selectedWarehouseId == null ? "" : " and l.warehouse_id=?";
        List<Object> queryArguments = new ArrayList<>(List.of(tenant(context), workspace(context), skuArgument));
        if (selectedWarehouseId != null) queryArguments.add(selectedWarehouseId);
        List<FefoAllocationPolicy.LotSnapshot> candidates = jdbc.query(
                "select l.id,l.sku_id,l.warehouse_id,l.status,l.stock_quantity-l.reserved_quantity available,l.unit,l.expiration_date,l.received_at "
                        + "from warehouse.inventory_lot l "
                        + "join warehouse.warehouse w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id "
                        + "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "where l.tenant_id=? and l.workspace_id=? and " + skuPredicate + " and l.status='AVAILABLE' "
                        + "and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity "
                        + "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' "
                        + warehousePredicate + " order by l.expiration_date,l.received_at,l.id"
                        + (lock ? " for update of l" : ""),
                (rs, row) -> new FefoAllocationPolicy.LotSnapshot(
                        rs.getObject("id").toString(), rs.getBigDecimal("available"), rs.getString("unit"),
                        rs.getObject("expiration_date", LocalDate.class), instant(rs, "received_at"),
                        InventoryLotStatus.valueOf(rs.getString("status")),
                        rs.getObject("sku_id") == null ? null : rs.getObject("sku_id").toString(),
                        rs.getObject("warehouse_id").toString()), queryArguments.toArray());
        candidates = applySafetyStock(context, candidates, effectiveSkuId);
        FefoAllocationPolicy.Result result = FefoAllocationPolicy.allocate(candidates, line.quantity(), line.unit(),
                effectiveSkuId == null ? null : effectiveSkuId.toString(),
                selectedWarehouseId == null ? null : selectedWarehouseId.toString(), LocalDate.now());
        return new WarehouseOperationsService.ProposalLine(line.catalogItemId(), line.quantity(), line.unit(),
                result.allocations().stream().map(item -> new WarehouseOperationsService.AllocationView(
                        item.lotId(), item.quantity(), item.unit(), item.expirationDate())).toList(),
                result.shortage(), result.complete(), effectiveSkuId == null ? null : effectiveSkuId.toString());
    }

    /**
     * Protects the latest eligible lots first so FEFO continues to consume the
     * oldest sellable stock while the configured warehouse reserve stays out of
     * allocation. The query remains tenant-scoped and runs inside the caller's
     * existing transaction/lock boundary.
     */
    protected List<FefoAllocationPolicy.LotSnapshot> applySafetyStock(
            CurrentAccessContext context, List<FefoAllocationPolicy.LotSnapshot> candidates, UUID skuId) {
        if (candidates.isEmpty() || skuId == null) return candidates;
        Map<UUID, SafetyStockRow> policies = jdbc.query(
                        "select warehouse_id,quantity,unit from warehouse.safety_stock_policy "
                                + "where tenant_id=? and workspace_id=? and sku_id=?",
                        (rs, row) -> Map.entry(rs.getObject("warehouse_id", UUID.class),
                                new SafetyStockRow(rs.getBigDecimal("quantity"), rs.getString("unit"))),
                        tenant(context), workspace(context), skuId)
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (policies.isEmpty()) return candidates;

        List<FefoAllocationPolicy.LotSnapshot> ordered = candidates.stream()
                .sorted(Comparator.comparing(FefoAllocationPolicy.LotSnapshot::expirationDate)
                        .thenComparing(FefoAllocationPolicy.LotSnapshot::receivedAt)
                        .thenComparing(FefoAllocationPolicy.LotSnapshot::lotId))
                .toList();
        Map<UUID, BigDecimal> remaining = new HashMap<>();
        policies.forEach((warehouse, policy) -> remaining.put(warehouse, policy.quantity()));
        List<FefoAllocationPolicy.LotSnapshot> adjusted = new ArrayList<>(ordered);
        for (int index = adjusted.size() - 1; index >= 0; index--) {
            FefoAllocationPolicy.LotSnapshot lot = adjusted.get(index);
            UUID warehouseId = uuid(lot.warehouseId());
            SafetyStockRow policy = policies.get(warehouseId);
            if (policy == null) continue;
            if (!policy.unit().equalsIgnoreCase(lot.unit())) throw error("INVENTORY_UNIT_MISMATCH", false);
            BigDecimal protectedQuantity = remaining.getOrDefault(warehouseId, BigDecimal.ZERO)
                    .min(lot.available()).max(BigDecimal.ZERO);
            remaining.put(warehouseId, remaining.get(warehouseId).subtract(protectedQuantity));
            if (protectedQuantity.signum() > 0) {
                adjusted.set(index, new FefoAllocationPolicy.LotSnapshot(lot.lotId(),
                        lot.available().subtract(protectedQuantity), lot.unit(), lot.expirationDate(),
                        lot.receivedAt(), lot.status(), lot.skuId(), lot.warehouseId()));
            }
        }
        return adjusted;
    }

    protected UUID selectedWarehouseId(OrderData order) {
        if (order.deliverySnapshot() == null || order.deliverySnapshot().isBlank()) return null;
        try {
            var warehouse = JsonMapper.shared().readTree(order.deliverySnapshot()).path("warehouse").path("id");
            return warehouse.isTextual() && !warehouse.asText().isBlank() ? uuid(warehouse.asText()) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    protected void lockSkuScope(CurrentAccessContext context, String skuKey) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant(context) + "|" + workspace(context) + "|warehouse-sku|" + skuKey);
    }

    protected SkuReference resolveSku(CurrentAccessContext context, String requestedSkuId, String legacyCatalogItemId) {
        List<SkuReference> matches;
        if (requestedSkuId != null && !requestedSkuId.isBlank()) {
            matches = jdbc.query("select id,legacy_catalog_item_id,sku_code from catalog_management.sellable_sku "
                            + "where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                    (rs, row) -> new SkuReference(rs.getObject("id", UUID.class), rs.getString("legacy_catalog_item_id"), rs.getString("sku_code")),
                    tenant(context), workspace(context), uuid(requestedSkuId));
        } else if (legacyCatalogItemId != null) {
            matches = jdbc.query("select id,legacy_catalog_item_id,sku_code from catalog_management.sellable_sku "
                            + "where tenant_id=? and workspace_id=? and legacy_catalog_item_id=? and status='ACTIVE'",
                    (rs, row) -> new SkuReference(rs.getObject("id", UUID.class), rs.getString("legacy_catalog_item_id"), rs.getString("sku_code")),
                    tenant(context), workspace(context), legacyCatalogItemId);
        } else {
            matches = List.of();
        }
        if (!matches.isEmpty()) return matches.getFirst();
        // V57 makes SKU identity mandatory for persisted Warehouse rows. A
        // legacy catalog item remains an input compatibility key, not a
        // second inventory identity.
        throw error("CATALOG_ITEM_NOT_FOUND", true);
    }

    protected void insertMovement(CurrentAccessContext context, UUID warehouseId, UUID zoneId, UUID lotId,
                                  String catalogItemId, UUID skuId, String movementType, BigDecimal quantity, String unit,
                                  BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore,
                                  BigDecimal reservedAfter, String reason, String correlation, Timestamp occurred) {
        insertMovement(tenant(context), workspace(context), warehouseId, zoneId, lotId, catalogItemId, skuId,
                movementType, quantity, unit, quantityBefore, quantityAfter, reservedBefore, reservedAfter,
                reason, context.membershipId().toString(), correlation, occurred);
    }

    protected void insertMovement(UUID tenantId, UUID workspaceId, UUID warehouseId, UUID zoneId, UUID lotId,
                                  String catalogItemId, UUID skuId, String movementType, BigDecimal quantity, String unit,
                                  BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore,
                                  BigDecimal reservedAfter, String reason, String actorMembershipId,
                                  String correlation, Timestamp occurred) {
        checkUpdated(jdbc.update(
                "insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,"
                        + "movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,"
                        + "actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?, ?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, warehouseId, zoneId, lotId, catalogItemId, skuId, movementType,
                quantity, unit, quantityBefore, quantityAfter, reservedBefore, reservedAfter, reason,
                actorMembershipId == null ? null : uuid(actorMembershipId), correlation == null ? "unknown" : correlation, occurred),
                "movement insert");
    }

    protected void appendEvent(CurrentAccessContext context, UUID aggregateId, String eventType, String aggregateType) {
        appendEvent(context, aggregateId, eventType, aggregateType, "ACTIVE", now());
    }

    protected void appendEvent(CurrentAccessContext context, UUID aggregateId, String eventType, String aggregateType,
                               String publicStatus, Timestamp occurred) {
        appendEvent(tenant(context), workspace(context), aggregateId, eventType, aggregateType, publicStatus, occurred,
                uuid(context.membershipId().toString()), eventType);
    }

    protected void appendEvent(UUID tenantId, UUID workspaceId, UUID aggregateId, String eventType, String aggregateType,
                               String publicStatus, Timestamp occurred) {
        appendEvent(tenantId, workspaceId, aggregateId, eventType, aggregateType, publicStatus, occurred, null,
                "reservation-expiry-" + aggregateId);
    }

    protected void appendEvent(UUID tenantId, UUID workspaceId, UUID aggregateId, String eventType, String aggregateType,
                               String publicStatus, Timestamp occurred, UUID actorMembershipId, String correlationId) {
        changeFeed.append(tenantId.toString(), workspaceId.toString(), null, aggregateType,
                aggregateId.toString(), eventType, publicStatus, occurred.getTime(), false);
        checkUpdated(jdbc.update(
                "insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, aggregateId, eventType, occurred, actorMembershipId,
                correlationId), "inventory event insert");
    }

    protected IdempotencyRecord idempotent(CurrentAccessContext context, String operation, String key) {
        return jdbc.query("select response_json,request_hash from warehouse.command_idempotency "
                                + "where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",
                        (rs, row) -> new IdempotencyRecord(rs.getString(1), rs.getString(2)),
                        tenant(context), workspace(context), operation, key)
                .stream().findFirst().orElse(null);
    }

    protected void lockIdempotency(CurrentAccessContext context, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant(context) + "|" + workspace(context) + "|" + operation + "|" + key);
    }

    protected void saveIdempotency(CurrentAccessContext context, String operation, String key, String hash, String resourceId) {
        int inserted = jdbc.update(
                "insert into warehouse.command_idempotency(tenant_id,workspace_id,operation,idempotency_key,request_hash,response_json,created_at) "
                        + "values (?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,operation,idempotency_key) do nothing",
                tenant(context), workspace(context), operation, key, hash, resourceId, now());
        if (inserted == 0) {
            IdempotencyRecord prior = idempotent(context, operation, key);
            if (prior == null) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            requireSamePayload(prior, hash);
        } else if (inserted != 1) {
            throw error("INVALID_REQUEST", false);
        }
    }

    protected void requireSamePayload(IdempotencyRecord record, String hash) {
        if (record.requestHash() != null && !record.requestHash().isBlank()
                && !record.requestHash().equalsIgnoreCase(hash)) {
            throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
        }
    }

    protected static String requestHash(String operation, Object... values) {
        String canonical = operation + "|" + java.util.Arrays.stream(values)
                .map(value -> value == null ? "<null>" : String.valueOf(value).trim())
                .collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    protected record Coordinates(BigDecimal latitude, BigDecimal longitude) { }
    protected record IdempotencyRecord(String resourceId, String requestHash) { }
    protected record SafetyStockRow(BigDecimal quantity, String unit) { }
    protected record LineData(UUID skuId, String catalogItemId, BigDecimal quantity, String unit) { }
    protected record SkuReference(UUID id, String legacyCatalogItemId, String skuCode) { }
    protected record OrderData(UUID id, String number, String status, long version, UUID clientAccountId,
                               String deliverySnapshot) { }
    protected record ScopeId(UUID tenantId, UUID workspaceId, UUID id) { }

    protected static UUID uuid(String value, boolean nullable) {
        if (nullable && (value == null || value.isBlank())) return null;
        return uuid(value);
    }

    protected static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException exception) { throw error("INVALID_REQUEST", false); }
    }

    protected static UUID uuidNullable(String value) { return value == null || value.isBlank() ? null : uuid(value); }
}
