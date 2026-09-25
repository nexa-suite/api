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
        if (!source.expirationDate().isAfter(LocalDate.now())) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
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
        UUID transferId = UUID.randomUUID();
        String mode = quantity.compareTo(source.onHand()) == 0 && source.reserved().signum() == 0 ? "FULL" : "PARTIAL";
        Timestamp requested = now();
        String correlation = correlationId == null ? "unknown" : correlationId;
        checkUpdated(jdbc.update("insert into warehouse.inventory_transfer"
                        + "(id,tenant_id,workspace_id,source_warehouse_id,source_zone_id,source_lot_id,destination_warehouse_id,destination_zone_id,destination_lot_id,"
                        + "sku_id,catalog_item_id,batch_number,expiration_date,requested_quantity,transferred_quantity,unit,mode,status,reason,"
                        + "source_quantity_before,source_quantity_after,destination_quantity_before,destination_quantity_after,source_version_before,source_version_after,destination_version_after,"
                        + "actor_membership_id,correlation_id,created_at,completed_at,version)"
                        + " values (?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,?,NULL,NULL,?,?,?,NULL,0)",
                transferId, tenant(context), workspace(context), source.warehouseUuid(), source.zoneUuid(), source.id(),
                destinationWarehouseId, destinationZoneId, source.skuId(), source.catalogItemId(), source.batchNumber(),
                source.expirationDate(), quantity, BigDecimal.ZERO, unit, mode, "REQUESTED", reason,
                source.onHand(), source.version(), context.membershipId().value(), correlation, requested),
                "transfer request insert");
        appendHistory(context, transferId, null, "REQUESTED", 0, correlation, requested);
        appendEvent(context, transferId, "warehouse.inventory.transfer.requested", "transfer", "REQUESTED", requested);
        saveIdempotency(context, operation, idempotencyKey, hash, transferId.toString());
        return transfer(context, transferId.toString());
    }

    @Override
    @Transactional
    public WarehouseOperationsService.TransferSummary dispatch(CurrentAccessContext context, String transferId,
            long expectedVersion, String idempotencyKey, String correlationId) {
        requireWrite(context);
        requireIdempotency(idempotencyKey);
        if (expectedVersion < 0) throw error("INVALID_REQUEST", false);
        String operation = "inventory-transfer-dispatch";
        String hash = requestHash(operation, transferId, expectedVersion);
        lockIdempotency(context, operation, idempotencyKey);
        IdempotencyRecord prior = idempotent(context, operation, idempotencyKey);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return transfer(context, prior.resourceId());
        }

        TransferState transfer = transferState(context, transferId, true);
        if (!transfer.status().equals("REQUESTED") || transfer.version() != expectedVersion) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        lockSkuScope(context, transfer.skuId().toString());
        requireActiveWarehouse(context, transfer.destinationWarehouseId());
        requireActiveZone(context, transfer.destinationWarehouseId(), transfer.destinationZoneId());
        TransferLot source = loadTransferLot(context, transfer.sourceLotId(), true);
        if (!source.warehouseUuid().equals(transfer.sourceWarehouseId())
                || !source.zoneUuid().equals(transfer.sourceZoneId())
                || !source.skuId().equals(transfer.skuId())) throw error("INVALID_REQUEST", false);
        if (source.status().equals("EXPIRED") || source.status().equals("DEPLETED")
                || !source.expirationDate().isAfter(LocalDate.now())) {
            throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        }
        if (source.onHand().subtract(source.reserved()).compareTo(transfer.quantity()) < 0) {
            throw error("INSUFFICIENT_AVAILABLE_STOCK", false);
        }
        if (source.status().equals("AVAILABLE") && !source.warehouseUuid().equals(transfer.destinationWarehouseId())) {
            SafetyStockRow safetyStock = safetyStock(context, source.warehouseUuid(), source.skuId());
            if (safetyStock != null && !safetyStock.unit().equalsIgnoreCase(source.unit())) {
                throw error("INVENTORY_UNIT_MISMATCH", false);
            }
            BigDecimal warehouseAvailable = usableWarehouseQuantity(context, source.warehouseUuid(), source.skuId());
            BigDecimal protectedQuantity = safetyStock == null ? BigDecimal.ZERO : safetyStock.quantity();
            BigDecimal transferable = warehouseAvailable.subtract(protectedQuantity).max(BigDecimal.ZERO)
                    .min(source.onHand().subtract(source.reserved()));
            if (transfer.quantity().compareTo(transferable) > 0) {
                throw error("INVENTORY_SAFETY_STOCK_PROTECTED", false);
            }
        }

        Timestamp dispatched = now();
        BigDecimal sourceAfter = source.onHand().subtract(transfer.quantity());
        String sourceStatusAfter = sourceAfter.signum() == 0 ? "DEPLETED" : source.status();
        checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1"
                        + " where tenant_id=? and workspace_id=? and id=? and version=?",
                sourceAfter, sourceStatusAfter, tenant(context), workspace(context), source.id(), source.version()),
                "transfer source dispatch", "CONCURRENCY_CONFLICT");
        insertMovement(context, source.warehouseUuid(), source.zoneUuid(), source.id(), source.catalogItemId(), source.skuId(),
                "TRANSFER_OUT", transfer.quantity(), source.unit(), source.onHand(), sourceAfter, source.reserved(),
                source.reserved(), transfer.reason(), correlationId, dispatched);
        String correlation = correlationId == null ? "unknown" : correlationId;
        checkUpdated(jdbc.update("update warehouse.inventory_transfer set status='IN_TRANSIT',transferred_quantity=requested_quantity,"
                        + "source_quantity_before=?,source_quantity_after=?,source_version_after=?,source_lot_status_at_dispatch=?,"
                        + "dispatched_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='REQUESTED' and version=?",
                source.onHand(), sourceAfter, source.version() + 1, source.status(), dispatched,
                tenant(context), workspace(context), transfer.id(), expectedVersion),
                "transfer dispatch", "CONCURRENCY_CONFLICT");
        appendHistory(context, transfer.id(), "REQUESTED", "IN_TRANSIT", expectedVersion + 1, correlation, dispatched);
        appendEvent(context, transfer.id(), "warehouse.inventory.transfer.dispatched", "transfer", "IN_TRANSIT", dispatched);
        saveIdempotency(context, operation, idempotencyKey, hash, transfer.id().toString());
        return transfer(context, transfer.id().toString());
    }

    @Override
    @Transactional
    public WarehouseOperationsService.TransferSummary receive(CurrentAccessContext context, String transferId,
            long expectedVersion, String idempotencyKey, String correlationId) {
        requireWrite(context);
        requireIdempotency(idempotencyKey);
        if (expectedVersion < 0) throw error("INVALID_REQUEST", false);
        String operation = "inventory-transfer-receipt";
        String hash = requestHash(operation, transferId, expectedVersion);
        lockIdempotency(context, operation, idempotencyKey);
        IdempotencyRecord prior = idempotent(context, operation, idempotencyKey);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return transfer(context, prior.resourceId());
        }

        TransferState transfer = transferState(context, transferId, true);
        if (!transfer.status().equals("IN_TRANSIT") || transfer.version() != expectedVersion) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        lockSkuScope(context, transfer.skuId().toString());
        requireActiveWarehouse(context, transfer.destinationWarehouseId());
        requireActiveZone(context, transfer.destinationWarehouseId(), transfer.destinationZoneId());
        TransferLot source = loadTransferLot(context, transfer.sourceLotId(), false);
        String destinationStatus = transfer.sourceStatusAtDispatch();
        if (!transfer.expirationDate().isAfter(LocalDate.now())) destinationStatus = "EXPIRED";

        TransferLot destination = destinationLot(context, transfer.destinationWarehouseId(), transfer.skuId(),
                transfer.batchNumber(), transfer.sourceLotId(), true);
        if (destination != null && !destination.zoneUuid().equals(transfer.destinationZoneId())) {
            throw error("INVALID_REQUEST", false);
        }
        if (destination != null && !destination.unit().equalsIgnoreCase(transfer.unit())) {
            throw error("INVENTORY_UNIT_MISMATCH", false);
        }
        if (destination != null && !destination.status().equals(destinationStatus)
                && !destination.status().equals("DEPLETED")) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);

        Timestamp received = now();
        UUID destinationLotId;
        BigDecimal destinationBefore;
        BigDecimal destinationAfter;
        long destinationVersionAfter;
        BigDecimal destinationReserved;
        if (destination == null) {
            destinationLotId = UUID.randomUUID();
            destinationBefore = BigDecimal.ZERO;
            destinationAfter = transfer.quantity();
            destinationVersionAfter = 0;
            destinationReserved = BigDecimal.ZERO;
            checkUpdated(jdbc.update("insert into warehouse.inventory_lot"
                            + "(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
                            + "stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,temperature_value)"
                            + " values (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                    destinationLotId, tenant(context), workspace(context), transfer.destinationWarehouseId(),
                    transfer.destinationZoneId(), transfer.catalogItemId(), transfer.skuId(), transfer.batchNumber(),
                    transfer.expirationDate(), Timestamp.from(source.receivedAt()), destinationAfter, transfer.unit(),
                    destinationStatus, source.temperatureRangeSnapshot(), source.temperatureValue()),
                    "transfer destination receipt insert");
        } else {
            destinationLotId = destination.id();
            destinationBefore = destination.onHand();
            destinationAfter = destination.onHand().add(transfer.quantity());
            destinationVersionAfter = destination.version() + 1;
            destinationReserved = destination.reserved();
            String nextStatus = destination.status().equals("DEPLETED") ? destinationStatus : destination.status();
            checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1"
                            + " where tenant_id=? and workspace_id=? and id=? and version=?",
                    destinationAfter, nextStatus, tenant(context), workspace(context), destination.id(), destination.version()),
                    "transfer destination receipt", "CONCURRENCY_CONFLICT");
        }
        insertMovement(context, transfer.destinationWarehouseId(), transfer.destinationZoneId(), destinationLotId,
                transfer.catalogItemId(), transfer.skuId(), "TRANSFER_IN", transfer.quantity(), transfer.unit(),
                destinationBefore, destinationAfter, destinationReserved, destinationReserved, transfer.reason(),
                correlationId, received);
        String correlation = correlationId == null ? "unknown" : correlationId;
        checkUpdated(jdbc.update("update warehouse.inventory_transfer set status='RECEIVED',destination_lot_id=?,"
                        + "destination_quantity_before=?,destination_quantity_after=?,destination_version_after=?,"
                        + "received_at=?,completed_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=?"
                        + " and status='IN_TRANSIT' and version=?",
                destinationLotId, destinationBefore, destinationAfter, destinationVersionAfter, received, received,
                tenant(context), workspace(context), transfer.id(), expectedVersion),
                "transfer receipt", "CONCURRENCY_CONFLICT");
        appendHistory(context, transfer.id(), "IN_TRANSIT", "RECEIVED", expectedVersion + 1, correlation, received);
        appendEvent(context, transfer.id(), "warehouse.inventory.transfer.received", "transfer", "RECEIVED", received);
        saveIdempotency(context, operation, idempotencyKey, hash, transfer.id().toString());
        return transfer(context, transfer.id().toString());
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

    private TransferState transferState(CurrentAccessContext context, String id, boolean lock) {
        return jdbc.query("select id,status,version,source_warehouse_id,source_zone_id,source_lot_id,"
                                + "destination_warehouse_id,destination_zone_id,sku_id,catalog_item_id,batch_number,"
                                + "expiration_date,requested_quantity,unit,reason,source_lot_status_at_dispatch "
                                + "from warehouse.inventory_transfer where tenant_id=? and workspace_id=? and id=?"
                                + (lock ? " for update" : ""),
                        (rs, row) -> new TransferState(rs.getObject("id", UUID.class), rs.getString("status"),
                                rs.getLong("version"), rs.getObject("source_warehouse_id", UUID.class),
                                rs.getObject("source_zone_id", UUID.class), rs.getObject("source_lot_id", UUID.class),
                                rs.getObject("destination_warehouse_id", UUID.class),
                                rs.getObject("destination_zone_id", UUID.class), rs.getObject("sku_id", UUID.class),
                                rs.getString("catalog_item_id"), rs.getString("batch_number"),
                                rs.getObject("expiration_date", LocalDate.class), rs.getBigDecimal("requested_quantity"),
                                rs.getString("unit"), rs.getString("reason"), rs.getString("source_lot_status_at_dispatch")),
                        tenant(context), workspace(context), uuid(id))
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_TRANSFER_NOT_FOUND", true));
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

    private void appendHistory(CurrentAccessContext context, UUID transferId, String fromStatus, String toStatus,
                               long version, String correlationId, Timestamp occurredAt) {
        checkUpdated(jdbc.update("insert into warehouse.inventory_transfer_history"
                        + "(id,tenant_id,workspace_id,transfer_id,from_status,to_status,transfer_version,"
                        + "actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant(context), workspace(context), transferId, fromStatus, toStatus, version,
                context.membershipId().value(), correlationId, occurredAt), "transfer history insert");
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
                + "source_version_before,source_version_after,destination_version_after,version,dispatched_at,received_at from warehouse.inventory_transfer";
    }

    private static WarehouseOperationsService.TransferSummary transfer(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID destinationLotId = rs.getObject("destination_lot_id", UUID.class);
        return new WarehouseOperationsService.TransferSummary(rs.getObject("id").toString(),
                rs.getObject("source_warehouse_id").toString(), rs.getObject("source_zone_id").toString(),
                rs.getObject("source_lot_id").toString(), rs.getObject("destination_warehouse_id").toString(),
                rs.getObject("destination_zone_id").toString(), destinationLotId == null ? null : destinationLotId.toString(),
                rs.getObject("sku_id").toString(), rs.getString("catalog_item_id"), rs.getString("batch_number"),
                rs.getObject("expiration_date", LocalDate.class), rs.getBigDecimal("requested_quantity"),
                rs.getBigDecimal("transferred_quantity"), rs.getString("unit"), rs.getString("mode"),
                rs.getString("status"), rs.getString("reason"), instant(rs, "created_at"),
                rs.getLong("source_version_before"), rs.getObject("source_version_after", Long.class),
                rs.getObject("destination_version_after", Long.class), rs.getLong("version"), instant(rs, "dispatched_at"),
                instant(rs, "received_at"));
    }

    private record SafetyStockRow(BigDecimal quantity, String unit) { }

    private record TransferState(UUID id, String status, long version, UUID sourceWarehouseId, UUID sourceZoneId,
                                 UUID sourceLotId, UUID destinationWarehouseId, UUID destinationZoneId, UUID skuId,
                                 String catalogItemId, String batchNumber, LocalDate expirationDate,
                                 BigDecimal quantity, String unit, String reason, String sourceStatusAtDispatch) { }

    private record TransferLot(UUID id, UUID warehouseUuid, UUID zoneUuid, String catalogItemId, UUID skuId,
                               String batchNumber, LocalDate expirationDate, java.time.Instant receivedAt,
                               BigDecimal onHand, BigDecimal reserved, String unit, String status, long version,
                               String temperatureRangeSnapshot, BigDecimal temperatureValue) {
        String warehouseId() { return warehouseUuid.toString(); }
        String zoneId() { return zoneUuid.toString(); }
    }
}
