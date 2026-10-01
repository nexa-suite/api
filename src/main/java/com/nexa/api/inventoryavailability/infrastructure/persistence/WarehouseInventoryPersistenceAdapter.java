package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryTemperatureHoldCommands;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLot;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLotStatus;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Cohesive JDBC adapter for receiving, lot lifecycle, movements and availability. */
@Repository
@Profile("!test")
public class WarehouseInventoryPersistenceAdapter extends WarehouseJdbcSupport
        implements WarehouseInventoryPersistencePort, InventoryTemperatureHoldCommands {
    private final BusinessEvidenceQuery businessEvidence;

    @Autowired
    public WarehouseInventoryPersistenceAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            SellableSkuQuery catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort operationalSettings,
            InventoryCommercialSource commercialSource,
            InventoryFulfillmentSource fulfillmentSource, WarehouseObjectAccess warehouseAccess,
            BusinessEvidenceQuery businessEvidence) {
        super(jdbc, changeFeed, catalog, transactionManager, operationalSettings, commercialSource, fulfillmentSource,
                warehouseAccess);
        this.businessEvidence = businessEvidence;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyTemperatureEvidenceTarget(CurrentAccessContext context, UUID lotId, UUID warehouseId,
                                                long expectedLotVersion) {
        if (context == null || lotId == null || warehouseId == null || expectedLotVersion < 0) {
            throw error("TEMPERATURE_EVIDENCE_INVALID", false);
        }
        WarehouseOperationsService.LotSummary lot = loadLot(context, lotId, true);
        if (!warehouseId.toString().equals(lot.warehouseId())) throw error("INVENTORY_LOT_NOT_FOUND", true);
        if (lot.version() != expectedLotVersion) throw error("INVENTORY_LOT_CONCURRENCY_CONFLICT", false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryTemperatureHoldCommands.PreventiveHoldResult recordPreventiveTemperatureExcursion(
            CurrentAccessContext context, InventoryTemperatureHoldCommands.PreventiveHoldRequest request) {
        if (context == null || request == null) throw error("TEMPERATURE_EVIDENCE_INVALID", false);
        WarehouseOperationsService.LotSummary lot = loadLot(context, request.lotId(), true);
        if (!request.warehouseId().toString().equals(lot.warehouseId())) {
            throw error("INVENTORY_LOT_NOT_FOUND", true);
        }
        if (lot.version() != request.expectedLotVersion()) {
            throw error("INVENTORY_LOT_CONCURRENCY_CONFLICT", false);
        }
        if (!"AVAILABLE".equals(lot.status()) || request.affectedQuantity().compareTo(lot.onHand()) > 0) {
            throw error("INVENTORY_TEMPERATURE_HOLD_CONFLICT", false);
        }
        if (!businessEvidence.isAvailablePhotoForSubject(tenant(context), workspace(context),
                request.evidenceObjectId(), "WAREHOUSE", request.warehouseId())) {
            throw error("BUSINESS_EVIDENCE_NOT_AVAILABLE", false);
        }

        InventoryLot aggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                InventoryLotStatus.valueOf(lot.status()));
        try {
            aggregate.markPreventiveTemperatureHold();
        } catch (IllegalStateException exception) {
            throw error("INVENTORY_TEMPERATURE_HOLD_CONFLICT", false);
        }

        Timestamp createdAt = now();
        UUID evaluationId = UUID.randomUUID();
        checkUpdated(jdbc.update("update warehouse.inventory_lot set status='HOLD',version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and status='AVAILABLE'",
                tenant(context), workspace(context), request.lotId(), request.expectedLotVersion()),
                "preventive temperature hold", "INVENTORY_LOT_CONCURRENCY_CONFLICT");
        jdbc.update("insert into warehouse.inventory_temperature_evaluation(id,tenant_id,workspace_id,lot_id,received_value,expected_min,expected_max,status,disposition,created_at,evidence_object_id,affected_quantity,actor_membership_id,observed_value,source_type,source_subject_id,source_subject_version,temperature_evidence_id,expected_lot_version) "
                        + "values (?,?,?,?,?,?,?,'OPEN','HOLD',?,?,?,?,?,'FULFILLMENT',?,?,?,?)",
                evaluationId, tenant(context), workspace(context), request.lotId(), null,
                request.minimumCelsius(), request.maximumCelsius(), createdAt, request.evidenceObjectId(),
                request.affectedQuantity(), context.membershipId().value(), request.valueCelsius(),
                request.fulfillmentId(), request.fulfillmentVersion(), request.temperatureEvidenceId(),
                request.expectedLotVersion());
        appendEvent(context, request.lotId(), "warehouse.lot.temperature-preventive-hold", "lot", "HOLD", createdAt);
        return new InventoryTemperatureHoldCommands.PreventiveHoldResult(evaluationId, request.lotId(), "HOLD",
                request.expectedLotVersion() + 1, request.affectedQuantity());
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
        query.append(warehouseIdPredicate(context, "warehouse_id", args));
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
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        String predicate = " where tenant_id=? and workspace_id=?" + warehouseIdPredicate(context, "warehouse_id", args);
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
        if (receipt == null) throw error("INVALID_REQUEST", false);
        UUID warehouse = uuidRequired(receipt.warehouseId(), "warehouseId");
        requireActiveWarehouse(context, warehouse);
        requireIdempotency(key);
        lockIdempotency(context, "inbound", key);
        String hash = receipt.temperatureEvidenceObjectId() == null
                ? requestHash("inbound", legacyReceiptHashValue(receipt))
                : requestHash("inbound-v2", receipt);
        IdempotencyRecord prior = idempotent(context, "inbound", key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        UUID zone = uuidRequired(receipt.zoneId(), "zoneId");
        String requestedCatalogItemId = receipt.catalogItemId() == null || receipt.catalogItemId().isBlank() ? null : bounded(receipt.catalogItemId(), "catalogItemId", 64);
        requireActiveZone(context, warehouse, zone);
        SkuReference sku = resolveSku(context, receipt.skuId(), requestedCatalogItemId);
        String catalogItemId = requestedCatalogItemId != null ? requestedCatalogItemId : sku.legacyCatalogItemId() == null || sku.legacyCatalogItemId().isBlank() ? sku.skuCode() : sku.legacyCatalogItemId();
        String unit = normalizedUnit(receipt.unit());
        String batch = bounded(receipt.batchNumber(), "batchNumber", 80);
        if (receipt.expirationDate() == null || !receipt.expirationDate().isAfter(LocalDate.now())) throw error("INVALID_REQUEST", false);
        if (receipt.quantity() == null || receipt.quantity().signum() <= 0) throw error("INVALID_REQUEST", false);
        validateTemperature(receipt.temperatureReading());
        String notes = boundedNullable(receipt.notes(), "notes", 2000);
        TemperatureRange skuRange = catalog.findPhysicalValidationPolicy(tenant(context), workspace(context), sku.id())
                .map(policy -> new TemperatureRange(policy.temperatureMin(), policy.temperatureMax()))
                .orElse(new TemperatureRange(null, null));
        if (skuRange.hasBounds() && receipt.temperatureReading() == null) throw error("TEMPERATURE_REQUIRED", false);
        TemperatureRange range = jdbc.query("select temperature_min,temperature_max from warehouse.storage_zone where tenant_id=? and workspace_id=? and id=?",
                (rs, n) -> new TemperatureRange(rs.getBigDecimal(1), rs.getBigDecimal(2)), tenant(context), workspace(context), zone)
                .stream().findFirst().orElse(new TemperatureRange(null, null));
        BigDecimal expectedMinimum = stricterMinimum(range.min(), skuRange.min());
        BigDecimal expectedMaximum = stricterMaximum(range.max(), skuRange.max());
        boolean temperatureExcursion = receipt.temperatureReading() != null
                && (!range.accepts(receipt.temperatureReading()) || !skuRange.accepts(receipt.temperatureReading()));
        UUID temperatureEvidenceId = receipt.temperatureEvidenceObjectId() == null ? null
                : uuidRequired(receipt.temperatureEvidenceObjectId(), "temperatureEvidenceObjectId");
        if (temperatureExcursion && temperatureEvidenceId == null) {
            throw error("BUSINESS_EVIDENCE_NOT_AVAILABLE", false);
        }
        if (temperatureEvidenceId != null && !businessEvidence.isAvailablePhotoForSubject(
                tenant(context), workspace(context), temperatureEvidenceId, "WAREHOUSE", warehouse)) {
            throw error("BUSINESS_EVIDENCE_NOT_AVAILABLE", false);
        }
        InventoryLot lotAggregate = InventoryLot.rehydrate("new-lot", BigDecimal.ZERO, BigDecimal.ZERO, unit,
                InventoryLotStatus.AVAILABLE);
        lotAggregate.receive(receipt.quantity());
        if (temperatureExcursion) lotAggregate.markHold();
        UUID id = UUID.randomUUID();
        Timestamp occurred = now();
        checkUpdated(jdbc.update("insert into warehouse.inventory_lot(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,temperature_value,temperature_evidence_object_id,temperature_recorded_by_membership_id,temperature_recorded_at) values (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?)",
                id, tenant(context), workspace(context), warehouse, zone, catalogItemId, sku.id(), batch, receipt.expirationDate(), occurred, receipt.quantity(), unit,
                temperatureExcursion ? "HOLD" : "AVAILABLE", range.snapshot(), receipt.temperatureReading(), temperatureEvidenceId,
                receipt.temperatureReading() == null ? null : context.membershipId().value(),
                receipt.temperatureReading() == null ? null : occurred), "lot insert");
        if (temperatureExcursion) {
            jdbc.update("insert into warehouse.inventory_temperature_evaluation(id,tenant_id,workspace_id,lot_id,received_value,expected_min,expected_max,status,disposition,created_at,evidence_object_id,affected_quantity,actor_membership_id,observed_value) values (?,?,?,?,?,?,?,'OPEN','HOLD',?,?,?,?,?)",
                    UUID.randomUUID(), tenant(context), workspace(context), id, receipt.temperatureReading(), expectedMinimum, expectedMaximum, occurred,
                    temperatureEvidenceId, receipt.quantity(), context.membershipId().value(), receipt.temperatureReading());
        }
        insertMovement(context, warehouse, zone, id, catalogItemId, sku.id(), "INBOUND_RECEIPT", receipt.quantity(), unit,
                BigDecimal.ZERO, receipt.quantity(), BigDecimal.ZERO, receipt.quantity(), notes, correlation, occurred);
        appendEvent(context, id, temperatureExcursion ? "warehouse.lot.temperature-hold" : "warehouse.lot.received", "lot", temperatureExcursion ? "HOLD" : "ACTIVE", occurred);
        saveIdempotency(context, "inbound", key, hash, id.toString());
        return loadLot(context, id, false);
    }

    private static String legacyReceiptHashValue(WarehouseOperationsService.Receipt receipt) {
        return "Receipt[warehouseId=" + receipt.warehouseId()
                + ", zoneId=" + receipt.zoneId()
                + ", catalogItemId=" + receipt.catalogItemId()
                + ", batchNumber=" + receipt.batchNumber()
                + ", expirationDate=" + receipt.expirationDate()
                + ", quantity=" + receipt.quantity()
                + ", unit=" + receipt.unit()
                + ", temperatureReading=" + receipt.temperatureReading()
                + ", notes=" + receipt.notes()
                + ", skuId=" + receipt.skuId() + "]";
    }

    private static BigDecimal stricterMinimum(BigDecimal zoneMinimum, BigDecimal skuMinimum) {
        if (zoneMinimum == null) return skuMinimum;
        if (skuMinimum == null) return zoneMinimum;
        return zoneMinimum.max(skuMinimum);
    }

    private static BigDecimal stricterMaximum(BigDecimal zoneMaximum, BigDecimal skuMaximum) {
        if (zoneMaximum == null) return skuMaximum;
        if (skuMaximum == null) return zoneMaximum;
        return zoneMaximum.min(skuMaximum);
    }

    public WarehouseOperationsService.LotSummary adjust(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                         boolean inbound, String reason, long expected, String key, String correlation) {
        context.requirePermission(PermissionKey.INVENTORY_ADJUST);
        return mutateStock(context, lotId, quantity, inbound ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT",
                "adjustment", reason, expected, key, correlation);
    }

    public WarehouseOperationsService.LotSummary waste(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                        String reason, long expected, String key, String correlation) {
        context.requirePermission(PermissionKey.INVENTORY_WASTE);
        return mutateStock(context, lotId, quantity, "WASTE", "waste", reason, expected, key, correlation);
    }

    private WarehouseOperationsService.LotSummary mutateStock(CurrentAccessContext context, String lotId, BigDecimal quantity,
                                                               String movementType, String operation, String reason, long expected,
                                                               String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        lockIdempotency(context, operation, key);
        if (quantity == null || quantity.signum() <= 0) throw error("INVALID_REQUEST", false);
        String normalizedReason = bounded(reason, "reason", 2000);
        String hash = operation.equals("adjustment")
                ? requestHash(operation, movementType, lotId, quantity, normalizedReason, expected)
                : requestHash(operation, lotId, quantity, normalizedReason, expected);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        if (operation.equals("adjustment")) {
            prior = legacyAdjustmentIdempotency(context, key, movementType, lotId, quantity, normalizedReason, expected);
            if (prior != null) return loadLot(context, uuid(prior.resourceId()), false);
        }
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
        context.requirePermission(PermissionKey.INVENTORY_WASTE);
        return transitionLot(context, lotId, "QUARANTINED", "warehouse.lot.quarantined", reason, expected, key, correlation);
    }

    public WarehouseOperationsService.LotSummary restoreLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) {
        context.requirePermission(PermissionKey.INVENTORY_RELEASE);
        return transitionLot(context, lotId, "AVAILABLE", "warehouse.lot.restored", reason, expected, key, correlation);
    }

    @Override
    public WarehouseOperationsService.LotSummary disposeLot(CurrentAccessContext context, String lotId, String disposition,
                                                             long expected, String reason, String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        String normalized = enumValue(disposition, "disposition", "RELEASE", "HOLD", "WASTE", "RETURN_TO_SUPPLIER");
        context.requirePermission(normalized.equals("RELEASE")
                ? PermissionKey.INVENTORY_RELEASE : PermissionKey.INVENTORY_WASTE);
        String normalizedReason = bounded(reason, "reason", 2000);
        String operation = "lot-disposition";
        String hash = requestHash(operation, lotId, expected, normalized, normalizedReason);
        lockIdempotency(context, operation, key);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) { requireSamePayload(prior, hash); return loadLot(context, uuid(prior.resourceId()), false); }
        prior = legacyDispositionIdempotency(context, key, normalized, lotId, expected, normalizedReason);
        if (prior != null) return loadLot(context, uuid(prior.resourceId()), false);
        UUID id = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, id, true);
        if (lot.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        if (lot.reserved().signum() > 0) throw error("INVENTORY_LOT_NOT_ALLOCATABLE", false);
        int openTemperatureEvaluations = jdbc.queryForObject(
                "select count(*) from warehouse.inventory_temperature_evaluation where tenant_id=? and workspace_id=? and lot_id=? and status='OPEN'",
                Integer.class, tenant(context), workspace(context), id);
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
        int resolvedTemperatureEvaluations = jdbc.update("update warehouse.inventory_temperature_evaluation set status='RESOLVED',disposition=?,resolution_reason=?,resolved_at=current_timestamp where tenant_id=? and workspace_id=? and lot_id=? and status='OPEN'",
                normalized, normalizedReason, tenant(context), workspace(context), id);
        if (resolvedTemperatureEvaluations != openTemperatureEvaluations) throw error("INVALID_REQUEST", false);
        appendEvent(context, id, "warehouse.lot.disposition-recorded", "lot", nextStatus, now());
        saveIdempotency(context, operation, key, hash, id.toString());
        return loadLot(context, id, false);
    }

    @Override
    @Transactional
    public WarehouseOperationsService.CycleCountRecord recordCycleCount(
            CurrentAccessContext context, String lotId,
            WarehouseOperationsService.CycleCountCommand command, long expectedLotVersion,
            String idempotencyKey, String correlationId) {
        requireWrite(context);
        requireIdempotency(idempotencyKey);
        if (command == null || expectedLotVersion < 0 || command.observedQuantity() == null
                || !fitsCycleCountQuantity(command.observedQuantity()) || command.observedQuantity().signum() < 0) {
            throw error("INVALID_REQUEST", false);
        }

        UUID lotUuid = uuid(lotId);
        WarehouseOperationsService.LotSummary lot = loadLot(context, lotUuid, true);
        String unit = normalizedUnit(command.unit());
        if (!lot.unit().equalsIgnoreCase(unit)) throw error("INVENTORY_UNIT_MISMATCH", false);
        String operation = "inventory-cycle-count";
        String hashInput = lengthPrefixed(lotUuid.toString())
                + lengthPrefixed(Long.toString(expectedLotVersion))
                + lengthPrefixed(canonicalQuantity(command.observedQuantity()))
                + lengthPrefixed(unit)
                + lengthPrefixed(context.membershipId().value().toString());
        String hash = requestHash(operation, hashInput);
        lockIdempotency(context, operation, idempotencyKey);
        IdempotencyRecord prior = idempotent(context, operation, idempotencyKey);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return cycleCount(context, prior.resourceId());
        }
        if (lot.version() != expectedLotVersion) throw error("CONCURRENCY_CONFLICT", false);

        BigDecimal observed = command.observedQuantity().stripTrailingZeros();
        String status = lot.onHand().compareTo(observed) == 0 ? "RECORDED" : "REQUESTED";
        UUID countId = UUID.randomUUID();
        Timestamp recordedAt = now();
        String correlation = correlationId == null || correlationId.isBlank() || "null".equals(correlationId)
                ? "unknown" : bounded(correlationId, "correlationId", 160);
        checkUpdated(jdbc.update("insert into warehouse.inventory_cycle_count"
                        + "(id,tenant_id,workspace_id,lot_id,warehouse_id,zone_id,lot_version,expected_quantity,"
                        + "observed_quantity,unit,status,actor_membership_id,correlation_id,recorded_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                countId, tenant(context), workspace(context), lotUuid, uuid(lot.warehouseId()), uuid(lot.zoneId()),
                lot.version(), lot.onHand(), observed, unit, status, context.membershipId().value(), correlation,
                recordedAt), "cycle count insert");
        saveIdempotency(context, operation, idempotencyKey, hash, countId.toString());
        return cycleCount(context, countId.toString());
    }

    @Override
    @Transactional
    public WarehouseOperationsService.CycleCountCorrection applyCycleCountCorrection(
            CurrentAccessContext context, String countId, long expectedLotVersion,
            String idempotencyKey, String correlationId) {
        requireWrite(context);
        requireIdempotency(idempotencyKey);
        if (expectedLotVersion < 0) throw error("INVALID_REQUEST", false);
        CycleCountRow count = cycleCountRow(context, uuid(countId));
        UUID lotUuid = uuid(count.lotId());
        WarehouseOperationsService.LotSummary lot = loadLot(context, lotUuid, true);
        if (!lot.warehouseId().equals(count.warehouseId()) || !lot.zoneId().equals(count.zoneId())) {
            throw error("INVENTORY_CYCLE_COUNT_NOT_FOUND", true);
        }

        String operation = "inventory-cycle-count-correction";
        String hashInput = lengthPrefixed(count.id()) + lengthPrefixed(Long.toString(expectedLotVersion))
                + lengthPrefixed(context.membershipId().value().toString());
        String hash = requestHash(operation, hashInput);
        lockIdempotency(context, operation, idempotencyKey);
        IdempotencyRecord prior = idempotent(context, operation, idempotencyKey);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return cycleCountCorrection(context, prior.resourceId());
        }
        if (!"REQUESTED".equals(count.status())) throw error("CYCLE_COUNT_CORRECTION_NOT_REQUESTED", false);
        if (expectedLotVersion != count.lotVersion() || lot.version() != count.lotVersion()
                || lot.onHand().compareTo(count.expectedQuantity()) != 0) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        if (!lot.unit().equalsIgnoreCase(count.unit())) throw error("INVENTORY_UNIT_MISMATCH", false);
        if (cycleCountCorrectionExists(context, uuid(count.id()))) {
            throw error("CYCLE_COUNT_CORRECTION_ALREADY_APPLIED", false);
        }

        BigDecimal before = lot.onHand();
        BigDecimal after = count.observedQuantity();
        BigDecimal delta = after.subtract(before);
        BigDecimal movementQuantity = delta.abs();
        boolean inbound = delta.signum() > 0;
        String movementType = inbound ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT";
        String reason = "Cycle count correction " + count.id();
        InventoryLot lotAggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                InventoryLotStatus.valueOf(lot.status()));
        try {
            if (inbound) lotAggregate.adjustIn(movementQuantity);
            else lotAggregate.adjustOut(movementQuantity);
        } catch (IllegalStateException exception) {
            throw error(inbound ? "INVENTORY_LOT_NOT_ALLOCATABLE" : "INSUFFICIENT_AVAILABLE_STOCK", false);
        }
        if (lotAggregate.onHand().compareTo(after) != 0) throw error("INVALID_REQUEST", false);

        checkUpdated(jdbc.update("update warehouse.inventory_lot set stock_quantity=?,status=?,version=version+1"
                        + " where tenant_id=? and workspace_id=? and id=? and version=?",
                after, lotAggregate.status().name(), tenant(context), workspace(context), lotUuid, count.lotVersion()),
                "cycle count stock correction", "CONCURRENCY_CONFLICT");
        UUID movementId = UUID.randomUUID();
        Timestamp recordedAt = now();
        String correlation = correlationId == null || correlationId.isBlank() || "null".equals(correlationId)
                ? "unknown" : bounded(correlationId, "correlationId", 160);
        checkUpdated(jdbc.update("insert into warehouse.stock_movement"
                        + "(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,movement_type,"
                        + "quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,"
                        + "actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                movementId, tenant(context), workspace(context), uuid(lot.warehouseId()), uuid(lot.zoneId()), lotUuid,
                lot.catalogItemId(), uuidNullable(lot.skuId()), movementType, movementQuantity, lot.unit(), before,
                after, lot.reserved(), lot.reserved(), reason, context.membershipId().value(), correlation, recordedAt),
                "cycle count correction movement insert");
        UUID correctionId = UUID.randomUUID();
        checkUpdated(jdbc.update("insert into warehouse.inventory_cycle_count_correction"
                        + "(id,tenant_id,workspace_id,cycle_count_id,lot_id,warehouse_id,zone_id,lot_version_before,"
                        + "lot_version_after,quantity_before,quantity_after,quantity_delta,unit,movement_id,"
                        + "actor_membership_id,correlation_id,recorded_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                correctionId, tenant(context), workspace(context), uuid(count.id()), lotUuid,
                uuid(lot.warehouseId()), uuid(lot.zoneId()), count.lotVersion(), count.lotVersion() + 1,
                before, after, delta, lot.unit(), movementId, context.membershipId().value(), correlation, recordedAt),
                "cycle count correction evidence insert");
        appendEvent(context, lotUuid, "warehouse.lot.adjusted", "lot", lotAggregate.status().name(), recordedAt);
        saveIdempotency(context, operation, idempotencyKey, hash, correctionId.toString());
        return cycleCountCorrection(context, correctionId.toString());
    }

    private WarehouseOperationsService.CycleCountRecord cycleCount(CurrentAccessContext context, String id) {
        return jdbc.query("select id,lot_id,warehouse_id,zone_id,lot_version,expected_quantity,observed_quantity,unit,"
                        + "status,actor_membership_id,recorded_at from warehouse.inventory_cycle_count"
                        + " where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new WarehouseOperationsService.CycleCountRecord(
                        rs.getObject("id", UUID.class).toString(), rs.getObject("lot_id", UUID.class).toString(),
                        rs.getObject("warehouse_id", UUID.class).toString(), rs.getObject("zone_id", UUID.class).toString(),
                        rs.getLong("lot_version"), rs.getBigDecimal("expected_quantity"),
                        rs.getBigDecimal("observed_quantity"), rs.getString("unit"), rs.getString("status"),
                        rs.getObject("actor_membership_id", UUID.class).toString(), instant(rs, "recorded_at")),
                tenant(context), workspace(context), uuid(id)).stream().findFirst()
                .orElseThrow(() -> error("INVENTORY_CYCLE_COUNT_NOT_FOUND", true));
    }

    private CycleCountRow cycleCountRow(CurrentAccessContext context, UUID id) {
        return jdbc.query("select id,lot_id,warehouse_id,zone_id,lot_version,expected_quantity,observed_quantity,unit,status"
                        + " from warehouse.inventory_cycle_count where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new CycleCountRow(rs.getObject("id", UUID.class).toString(),
                        rs.getObject("lot_id", UUID.class).toString(), rs.getObject("warehouse_id", UUID.class).toString(),
                        rs.getObject("zone_id", UUID.class).toString(), rs.getLong("lot_version"),
                        rs.getBigDecimal("expected_quantity"), rs.getBigDecimal("observed_quantity"),
                        rs.getString("unit"), rs.getString("status")), tenant(context), workspace(context), id)
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_CYCLE_COUNT_NOT_FOUND", true));
    }

    private WarehouseOperationsService.CycleCountCorrection cycleCountCorrection(CurrentAccessContext context, String id) {
        return jdbc.query("select id,cycle_count_id,lot_id,warehouse_id,zone_id,lot_version_before,lot_version_after,"
                        + "quantity_before,quantity_after,quantity_delta,unit,actor_membership_id,recorded_at"
                        + " from warehouse.inventory_cycle_count_correction where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new WarehouseOperationsService.CycleCountCorrection(
                        rs.getObject("id", UUID.class).toString(), rs.getObject("cycle_count_id", UUID.class).toString(),
                        rs.getObject("lot_id", UUID.class).toString(), rs.getObject("warehouse_id", UUID.class).toString(),
                        rs.getObject("zone_id", UUID.class).toString(), rs.getLong("lot_version_before"),
                        rs.getLong("lot_version_after"), rs.getBigDecimal("quantity_before"),
                        rs.getBigDecimal("quantity_after"), rs.getBigDecimal("quantity_delta"), rs.getString("unit"),
                        rs.getObject("actor_membership_id", UUID.class).toString(), instant(rs, "recorded_at")),
                tenant(context), workspace(context), uuid(id)).stream().findFirst()
                .orElseThrow(() -> error("INVENTORY_CYCLE_COUNT_CORRECTION_NOT_FOUND", true));
    }

    private boolean cycleCountCorrectionExists(CurrentAccessContext context, UUID countId) {
        return exists("select 1 from warehouse.inventory_cycle_count_correction"
                        + " where tenant_id=? and workspace_id=? and cycle_count_id=?",
                tenant(context), workspace(context), countId);
    }

    private static boolean fitsCycleCountQuantity(BigDecimal quantity) {
        BigDecimal normalized = quantity.stripTrailingZeros();
        int fractionalDigits = Math.max(0, normalized.scale());
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        return fractionalDigits <= 4 && integerDigits <= 15;
    }

    private static String canonicalQuantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private static String lengthPrefixed(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private record CycleCountRow(String id, String lotId, String warehouseId, String zoneId, long lotVersion,
                                 BigDecimal expectedQuantity, BigDecimal observedQuantity, String unit, String status) { }

    private IdempotencyRecord legacyAdjustmentIdempotency(CurrentAccessContext context, String key,
                                                            String movementType, String lotId, BigDecimal quantity,
                                                            String reason, long expected) {
        String requestedOperation = movementType.toLowerCase(java.util.Locale.ROOT);
        for (String legacyOperation : List.of("adjustment_in", "adjustment_out")) {
            IdempotencyRecord prior = idempotent(context, legacyOperation, key);
            if (prior == null) continue;
            if (!legacyOperation.equals(requestedOperation)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            requireSamePayload(prior, requestHash(legacyOperation, lotId, quantity, reason, expected));
            return prior;
        }
        return null;
    }

    private IdempotencyRecord legacyDispositionIdempotency(CurrentAccessContext context, String key,
                                                             String disposition, String lotId, long expected,
                                                             String reason) {
        String requestedOperation = "lot-disposition-" + disposition.toLowerCase(java.util.Locale.ROOT);
        for (String legacyDisposition : List.of("RELEASE", "HOLD", "WASTE", "RETURN_TO_SUPPLIER")) {
            String legacyOperation = "lot-disposition-" + legacyDisposition.toLowerCase(java.util.Locale.ROOT);
            IdempotencyRecord prior = idempotent(context, legacyOperation, key);
            if (prior == null) continue;
            if (!legacyOperation.equals(requestedOperation)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            requireSamePayload(prior, requestHash(legacyOperation, lotId, expected, disposition, reason));
            return prior;
        }
        return null;
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
        if (!context.allows(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission.WAREHOUSE_READ)
                && !context.allows(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission.CATALOG_READ)) throw error("FORBIDDEN", false);
        return queryAvailability(context, ids, null);
    }

    @Transactional(readOnly = true)
    public List<WarehouseOperationsService.Availability> warehouseAvailability(CurrentAccessContext context, String warehouseId, List<String> ids) {
        requireRead(context);
        UUID warehouse = uuid(warehouseId);
        requireActiveWarehouse(context, warehouse);
        return queryAvailability(context, ids, warehouse);
    }

    private List<WarehouseOperationsService.Availability> queryAvailability(CurrentAccessContext context, List<String> ids, UUID warehouseId) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_PAGE_SIZE || ids.stream().anyMatch(id -> id == null || id.isBlank())) throw error("INVALID_REQUEST", false);
        List<String> normalized = ids.stream().map(id -> bounded(id, "catalogItemId", 64)).distinct().toList();
        String placeholders = normalized.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context))); args.addAll(normalized);
        String warehousePredicate;
        if (warehouseId != null) {
            warehousePredicate = " and warehouse_id=?";
            args.add(warehouseId);
        } else if (context.allows(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission.WAREHOUSE_READ)) {
            warehousePredicate = warehouseIdPredicate(context, "warehouse_id", args);
        } else {
            warehousePredicate = "";
        }
        List<UUID> skuIds = jdbc.query("select distinct sku_id from warehouse.inventory_lot where tenant_id=? and workspace_id=? "
                        + "and catalog_item_id in (" + placeholders + ") and sku_id is not null" + warehousePredicate,
                (rs, row) -> rs.getObject(1, UUID.class), args.toArray());
        CatalogSkuSnapshots.Input catalogSnapshots = CatalogSkuSnapshots.of(tenant(context), workspace(context),
                catalog.findInventoryPolicies(tenant(context), workspace(context), skuIds));
        List<AvailabilityQuantities> rows = jdbc.query("with " + catalogSnapshots.cte() + ", active_backing as ("
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
                        + "join catalog_sku_snapshot sku on sku.id=l.sku_id and sku.tenant_id=l.tenant_id and sku.workspace_id=l.workspace_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "left join warehouse.safety_stock_policy ss on ss.tenant_id=l.tenant_id and ss.workspace_id=l.workspace_id "
                        + "and ss.warehouse_id=l.warehouse_id and ss.sku_id=l.sku_id "
                        + "left join active_backing on active_backing.tenant_id=l.tenant_id and active_backing.workspace_id=l.workspace_id "
                        + "and active_backing.catalog_item_id=l.catalog_item_id and active_backing.warehouse_id=l.warehouse_id "
                        + "where l.tenant_id=? and l.workspace_id=? and l.catalog_item_id in (" + placeholders + ") "
                        + warehousePredicate.replace("warehouse_id", "l.warehouse_id") + " "
                        + "group by l.catalog_item_id,l.warehouse_id",
                (rs, row) -> new AvailabilityQuantities(rs.getString("catalog_item_id"),
                        rs.getBigDecimal("physical_quantity"), rs.getBigDecimal("eligible_quantity"),
                        rs.getBigDecimal("safety_stock"), rs.getBigDecimal("active_backing_quantity")),
                catalogSnapshots.prepend(args.toArray()));
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

    private void requireRead(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission.WAREHOUSE_READ); }
    private void requireWrite(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission.WAREHOUSE_WRITE); }

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
