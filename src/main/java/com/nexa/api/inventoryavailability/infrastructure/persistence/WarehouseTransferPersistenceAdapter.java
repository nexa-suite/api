package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseTransferPersistencePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Atomic, tenant-scoped transfer command using the existing SKU lock and FEFO policy. */
@Repository
@Profile("!test")
public class WarehouseTransferPersistenceAdapter extends WarehouseJdbcSupport
        implements WarehouseTransferPersistencePort {

    @Autowired
    public WarehouseTransferPersistenceAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort operationalSettings) {
        super(jdbc, changeFeed, catalog, transactionManager, operationalSettings);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.TransferSummary> transfers(
            CurrentAccessContext context, String sourceWarehouseId, String destinationWarehouseId,
            int page, int size) {
        requireRead(context);
        pageCheck(page, size);
        StringBuilder predicate = new StringBuilder(" where tenant_id=? and workspace_id=?");
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (sourceWarehouseId != null && !sourceWarehouseId.isBlank()) {
            predicate.append(" and source_warehouse_id=?");
            args.add(uuid(sourceWarehouseId));
        }
        if (destinationWarehouseId != null && !destinationWarehouseId.isBlank()) {
            predicate.append(" and destination_warehouse_id=?");
            args.add(uuid(destinationWarehouseId));
        }
        String from = " from warehouse.inventory_transfer";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<WarehouseOperationsService.TransferSummary> items = jdbc.query(
                transferSelect() + predicate + " order by created_at desc,id desc limit ? offset ?",
                (rs, row) -> transfer(rs), pageArgs.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size,
                count("select count(*)" + from + predicate, args.toArray()));
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.TransferSummary transfer(CurrentAccessContext context, String id) {
        requireRead(context);
        return jdbc.query(transferSelect() + " where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> transfer(rs), tenant(context), workspace(context), uuid(id))
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_TRANSFER_NOT_FOUND", true));
    }

    @Override
    public WarehouseOperationsService.TransferSummary transfer(
            CurrentAccessContext context, WarehouseOperationsService.TransferCommand command,
            long expectedSourceVersion, String idempotencyKey, String correlationId) {
        requireWrite(context);
        requireIdempotency(idempotencyKey);
        if (command == null || expectedSourceVersion < 0) throw error("INVALID_REQUEST", false);

        UUID destinationWarehouseId = uuidRequired(command.destinationWarehouseId(), "destinationWarehouseId");
        UUID destinationZoneId = uuidRequired(command.destinationZoneId(), "destinationZoneId");
        BigDecimal quantity = command.quantity();
        if (quantity == null || quantity.signum() <= 0) throw error("INVALID_REQUEST", false);
        String requestedUnit = command.unit() == null || command.unit().isBlank() ? null : normalizedUnit(command.unit());
        String reason = bounded(command.reason(), "reason", 2000);
        String operation = "inventory-transfer";
        String hash = requestHash(operation, command.sourceLotId(), command.sourceWarehouseId(), command.sourceZoneId(),
                destinationWarehouseId, destinationZoneId, command.skuId(), command.catalogItemId(), quantity,
                requestedUnit, reason, expectedSourceVersion);
        lockIdempotency(context, operation, idempotencyKey);
        IdempotencyRecord prior = idempotent(context, operation, idempotencyKey);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return transfer(context, prior.resourceId());
        }

        requireActiveWarehouse(context, destinationWarehouseId);
        requireActiveZone(context, destinationWarehouseId, destinationZoneId);

        TransferLot sourceHint = command.sourceLotId() == null || command.sourceLotId().isBlank()
                ? null : loadTransferLot(context, uuid(command.sourceLotId()), false);
        SkuReference requestedSku = sourceHint == null
                ? requestedSku(context, command.skuId(), command.catalogItemId()) : null;
        UUID sourceWarehouseId = sourceHint == null
                ? uuidRequired(command.sourceWarehouseId(), "sourceWarehouseId") : uuid(sourceHint.warehouseId());
        UUID skuId = sourceHint == null ? requestedSku.id() : sourceHint.skuId();
        lockSkuScope(context, skuId.toString());

        TransferLot source = sourceHint == null
                ? selectFefoSource(context, sourceWarehouseId, skuId, command.catalogItemId(), quantity, requestedUnit)
                : sourceHint;
        TransferLot destination = destinationLot(context, destinationWarehouseId, skuId, source.batchNumber(), source.id(), false);
        lockTransferLots(context, source.id(), destination == null ? null : destination.id());
        source = loadTransferLot(context, source.id(), false);
        if (source.version() != expectedSourceVersion) throw error("CONCURRENCY_CONFLICT", false);
        if (source.status().equals("EXPIRED") || source.status().equals("DEPLETED")) {
            throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        }
        if (command.sourceWarehouseId() != null && !command.sourceWarehouseId().isBlank()
                && !source.warehouseId().equals(command.sourceWarehouseId())) throw error("INVALID_REQUEST", false);
        if (command.sourceZoneId() != null && !command.sourceZoneId().isBlank()
                && !source.zoneId().equals(command.sourceZoneId())) throw error("INVALID_REQUEST", false);
        if (requestedSku != null && !requestedSku.id().equals(source.skuId())) throw error("INVALID_REQUEST", false);
        if (requestedUnit != null && !requestedUnit.equalsIgnoreCase(source.unit())) {
            throw error("INVENTORY_UNIT_MISMATCH", false);
        }
        String unit = source.unit();
        if (source.warehouseId().equals(destinationWarehouseId.toString())
                && source.zoneId().equals(destinationZoneId.toString())) throw error("INVALID_REQUEST", false);

        BigDecimal sourceAvailable = source.onHand().subtract(source.reserved());
        if (quantity.compareTo(sourceAvailable) > 0) throw error("INSUFFICIENT_AVAILABLE_STOCK", false);
        if (source.status().equals("AVAILABLE") && !source.warehouseId().equals(destinationWarehouseId.toString())) {
            SafetyStockRow safetyStock = safetyStock(context, UUID.fromString(source.warehouseId()), source.skuId());
            if (safetyStock != null && !safetyStock.unit().equalsIgnoreCase(unit)) {
                throw error("INVENTORY_UNIT_MISMATCH", false);
            }
            BigDecimal warehouseAvailable = usableWarehouseQuantity(context, UUID.fromString(source.warehouseId()), source.skuId());
            BigDecimal protectedQuantity = safetyStock == null ? BigDecimal.ZERO : safetyStock.quantity();
            BigDecimal transferable = warehouseAvailable.subtract(protectedQuantity).max(BigDecimal.ZERO).min(sourceAvailable);
            if (quantity.compareTo(transferable) > 0) throw error("INVENTORY_SAFETY_STOCK_PROTECTED", false);
        }

        destination = destinationLot(context, destinationWarehouseId, skuId, source.batchNumber(), source.id(), false);
        if (destination != null && !destination.unit().equalsIgnoreCase(unit)) throw error("INVENTORY_UNIT_MISMATCH", false);
        if (destination != null && !destination.status().equals(source.status())
                && !destination.status().equals("DEPLETED")) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);

        Timestamp occurred = now();
        BigDecimal sourceAfter = source.onHand().subtract(quantity);
        String sourceStatusAfter = sourceAfter.signum() == 0 ? "DEPLETED" : source.status();
        checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1"
                        + " where tenant_id=? and workspace_id=? and id=? and version=?",
                sourceAfter, sourceStatusAfter, tenant(context), workspace(context), source.id(), expectedSourceVersion),
                "transfer source update", "CONCURRENCY_CONFLICT");

        UUID destinationLotId;
        BigDecimal destinationBefore;
        BigDecimal destinationAfter;
        long destinationVersionAfter;
        if (destination == null) {
            destinationLotId = UUID.randomUUID();
            destinationBefore = BigDecimal.ZERO;
            destinationAfter = quantity;
            destinationVersionAfter = 0;
            checkUpdated(jdbc.update("insert into warehouse.inventory_lot"
                            + "(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
                            + "stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,temperature_value)"
                            + " values (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                    destinationLotId, tenant(context), workspace(context), destinationWarehouseId, destinationZoneId,
                    source.catalogItemId(), source.skuId(), source.batchNumber(), source.expirationDate(), Timestamp.from(source.receivedAt()),
                    destinationAfter, unit, source.status(), source.temperatureRangeSnapshot(), source.temperatureValue()),
                    "transfer destination insert");
        } else {
            destinationLotId = destination.id();
            destinationBefore = destination.onHand();
            destinationAfter = destination.onHand().add(quantity);
            String destinationStatus = destination.status().equals("DEPLETED") ? source.status() : destination.status();
            destinationVersionAfter = destination.version() + 1;
            checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1"
                            + " where tenant_id=? and workspace_id=? and id=? and version=?",
                    destinationAfter, destinationStatus, tenant(context), workspace(context), destination.id(), destination.version()),
                    "transfer destination update", "CONCURRENCY_CONFLICT");
        }

        insertMovement(context, source.warehouseUuid(), source.zoneUuid(), source.id(), source.catalogItemId(), source.skuId(),
                "TRANSFER_OUT", quantity, unit, source.onHand(), sourceAfter, source.reserved(), source.reserved(), reason, correlationId, occurred);
        insertMovement(context, destinationWarehouseId, destinationZoneId, destinationLotId, source.catalogItemId(), source.skuId(),
                "TRANSFER_IN", quantity, unit, destinationBefore, destinationAfter,
                destination == null ? BigDecimal.ZERO : destination.reserved(),
                destination == null ? BigDecimal.ZERO : destination.reserved(), reason, correlationId, occurred);

        UUID transferId = UUID.randomUUID();
        String mode = quantity.compareTo(source.onHand()) == 0 && source.reserved().signum() == 0 ? "FULL" : "PARTIAL";
        checkUpdated(jdbc.update("insert into warehouse.inventory_transfer"
                        + "(id,tenant_id,workspace_id,source_warehouse_id,source_zone_id,source_lot_id,destination_warehouse_id,destination_zone_id,destination_lot_id,"
                        + "sku_id,catalog_item_id,batch_number,expiration_date,requested_quantity,transferred_quantity,unit,mode,status,reason,"
                        + "source_quantity_before,source_quantity_after,destination_quantity_before,destination_quantity_after,source_version_before,source_version_after,destination_version_after,"
                        + "actor_membership_id,correlation_id,created_at,completed_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?,?,?)",
                transferId, tenant(context), workspace(context), source.warehouseUuid(), source.zoneUuid(), source.id(),
                destinationWarehouseId, destinationZoneId, destinationLotId, source.skuId(), source.catalogItemId(),
                source.batchNumber(), source.expirationDate(), quantity, quantity, unit, mode, "COMPLETED", reason,
                source.onHand(), sourceAfter, destinationBefore, destinationAfter, source.version(), expectedSourceVersion + 1,
                destinationVersionAfter, context.membershipId().value(), correlationId == null ? "unknown" : correlationId,
                occurred, occurred), "transfer insert");
        appendEvent(context, transferId, "warehouse.inventory.transfer.completed", "transfer", "COMPLETED", occurred);
        saveIdempotency(context, operation, idempotencyKey, hash, transferId.toString());
        return transfer(context, transferId.toString());
    }

    private TransferLot selectFefoSource(CurrentAccessContext context, UUID sourceWarehouseId, UUID skuId,
                                         String catalogItemId, BigDecimal quantity, String unit) {
        String legacy = catalogItemId == null || catalogItemId.isBlank() ? skuId.toString() : bounded(catalogItemId, "catalogItemId", 64);
        WarehouseOperationsService.ProposalLine proposal = proposal(context,
                new LineData(skuId, legacy, quantity, unit), false, sourceWarehouseId);
        if (proposal.allocations().isEmpty()) throw error("INSUFFICIENT_AVAILABLE_STOCK", false);
        if (proposal.allocations().size() != 1 || proposal.shortage().signum() > 0) {
            throw error("INVENTORY_TRANSFER_SINGLE_LOT_REQUIRED", false);
        }
        return loadTransferLot(context, uuid(proposal.allocations().getFirst().lotId()), false);
    }

    private TransferLot loadTransferLot(CurrentAccessContext context, UUID id, boolean lock) {
        return jdbc.query("select id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
                                + "stock_quantity,reserved_quantity,unit,status,version,temperature_range_snapshot,temperature_value"
                                + " from warehouse.inventory_lot where tenant_id=? and workspace_id=? and id=?"
                                + (lock ? " for update" : ""), (rs, row) -> transferLot(rs),
                        tenant(context), workspace(context), id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_LOT_NOT_FOUND", true));
    }

    private TransferLot destinationLot(CurrentAccessContext context, UUID warehouseId, UUID skuId,
                                       String batchNumber, UUID sourceLotId, boolean lock) {
        return jdbc.query("select id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
                                + "stock_quantity,reserved_quantity,unit,status,version,temperature_range_snapshot,temperature_value"
                                + " from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=? and sku_id=?"
                                + " and batch_number=? and id<>?" + (lock ? " for update" : ""),
                        (rs, row) -> transferLot(rs), tenant(context), workspace(context), warehouseId, skuId, batchNumber, sourceLotId)
                .stream().findFirst().orElse(null);
    }

    /** Locks source and destination lots in the shared order used by allocation paths. */
    private void lockTransferLots(CurrentAccessContext context, UUID sourceLotId, UUID destinationLotId) {
        List<UUID> ids = java.util.stream.Stream.of(sourceLotId, destinationLotId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (ids.isEmpty()) return;
        String placeholders = ids.stream().map(value -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        args.addAll(ids);
        jdbc.query("select l.id from warehouse.inventory_lot l where l.tenant_id=? and l.workspace_id=?"
                        + " and l.id in (" + placeholders + ") order by " + WarehouseLotLockOrder.inventoryLot("l")
                        + " for update of l",
                (rs, row) -> rs.getObject("id", UUID.class), args.toArray());
    }

    private SafetyStockRow safetyStock(CurrentAccessContext context, UUID warehouseId, UUID skuId) {
        return jdbc.query("select quantity,unit from warehouse.safety_stock_policy where tenant_id=? and workspace_id=?"
                                + " and warehouse_id=? and sku_id=?",
                        (rs, row) -> new SafetyStockRow(rs.getBigDecimal("quantity"), rs.getString("unit")),
                        tenant(context), workspace(context), warehouseId, skuId)
                .stream().findFirst().orElse(null);
    }

    private BigDecimal usableWarehouseQuantity(CurrentAccessContext context, UUID warehouseId, UUID skuId) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(l.stock_quantity-l.reserved_quantity),0)"
                        + " from warehouse.inventory_lot l"
                        + " join warehouse.warehouse w on w.id=l.warehouse_id and w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id"
                        + " join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id"
                        + " where l.tenant_id=? and l.workspace_id=? and l.warehouse_id=? and l.sku_id=?"
                        + " and l.status='AVAILABLE' and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity"
                        + " and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE'",
                BigDecimal.class, tenant(context), workspace(context), warehouseId, skuId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private void requireRead(CurrentAccessContext context) {
        context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ);
    }

    private void requireWrite(CurrentAccessContext context) {
        context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_WRITE);
    }

    private SkuReference requestedSku(CurrentAccessContext context, String skuId, String catalogItemId) {
        if (skuId != null && !skuId.isBlank()) {
            return isUuid(skuId) ? resolveSku(context, skuId, null) : resolveSku(context, null, bounded(skuId, "skuId", 64));
        }
        return resolveSku(context, null, bounded(catalogItemId, "catalogItemId", 64));
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static TransferLot transferLot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferLot(rs.getObject("id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("catalog_item_id"),
                rs.getObject("sku_id", UUID.class), rs.getString("batch_number"),
                rs.getObject("expiration_date", LocalDate.class), instant(rs, "received_at"),
                rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("reserved_quantity"), rs.getString("unit"),
                rs.getString("status"), rs.getLong("version"), rs.getString("temperature_range_snapshot"),
                rs.getBigDecimal("temperature_value"));
    }

    private static String transferSelect() {
        return "select id,source_warehouse_id,source_zone_id,source_lot_id,destination_warehouse_id,destination_zone_id,destination_lot_id,"
                + "sku_id,catalog_item_id,batch_number,expiration_date,requested_quantity,transferred_quantity,unit,mode,status,reason,created_at,"
                + "source_version_before,source_version_after,destination_version_after from warehouse.inventory_transfer";
    }

    private static WarehouseOperationsService.TransferSummary transfer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WarehouseOperationsService.TransferSummary(rs.getObject("id").toString(),
                rs.getObject("source_warehouse_id").toString(), rs.getObject("source_zone_id").toString(),
                rs.getObject("source_lot_id").toString(), rs.getObject("destination_warehouse_id").toString(),
                rs.getObject("destination_zone_id").toString(), rs.getObject("destination_lot_id").toString(),
                rs.getObject("sku_id").toString(), rs.getString("catalog_item_id"), rs.getString("batch_number"),
                rs.getObject("expiration_date", LocalDate.class), rs.getBigDecimal("requested_quantity"),
                rs.getBigDecimal("transferred_quantity"), rs.getString("unit"), rs.getString("mode"),
                rs.getString("status"), rs.getString("reason"), instant(rs, "created_at"),
                rs.getLong("source_version_before"), rs.getLong("source_version_after"),
                rs.getLong("destination_version_after"));
    }

    private record SafetyStockRow(BigDecimal quantity, String unit) { }

    private record TransferLot(UUID id, UUID warehouseUuid, UUID zoneUuid, String catalogItemId, UUID skuId,
                               String batchNumber, LocalDate expirationDate, java.time.Instant receivedAt,
                               BigDecimal onHand, BigDecimal reserved, String unit, String status, long version,
                               String temperatureRangeSnapshot, BigDecimal temperatureValue) {
        String warehouseId() { return warehouseUuid.toString(); }
        String zoneId() { return zoneUuid.toString(); }
    }
}
