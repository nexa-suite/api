package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import com.nexa.api.warehouse.application.port.WarehouseOperationalSettingsPort;
import com.nexa.api.warehouse.application.port.WarehouseReservationPersistencePort;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLot;
import com.nexa.api.warehouse.domain.model.inventorylot.InventoryLotStatus;
import com.nexa.api.warehouse.domain.model.inventoryreservation.InventoryReservation;
import com.nexa.api.warehouse.domain.model.inventoryreservation.InventoryReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nexa.api.warehouse.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Cohesive JDBC adapter for FEFO proposals, reservations and fulfillment readiness. */
@Repository
@Profile("!test")
public class WarehouseReservationPersistenceAdapter extends WarehouseJdbcSupport
        implements WarehouseReservationPersistencePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(WarehouseReservationPersistenceAdapter.class);

    @Autowired
    public WarehouseReservationPersistenceAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            WarehouseOperationalSettingsPort operationalSettings) {
        super(jdbc, changeFeed, catalog, transactionManager, operationalSettings);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.ReservationPreview preview(CurrentAccessContext context, String orderId) {
        requireFulfillmentRead(context);
        OrderData order = loadOrder(context, uuid(orderId), false);
        UUID selectedWarehouseId = selectedWarehouseId(order);
        List<WarehouseOperationsService.ProposalLine> proposals = lines(context, order.id()).stream()
                .map(line -> proposal(context, line, false, selectedWarehouseId)).toList();
        return new WarehouseOperationsService.ReservationPreview(order.id().toString(), order.number(), proposals,
                proposals.stream().allMatch(WarehouseOperationsService.ProposalLine::complete), Instant.now(),
                "Preview only — inventory is not reserved.");
    }

    public WarehouseOperationsService.ReservationDetail reserve(CurrentAccessContext context, String orderId, long expected,
                                                                  String key, String correlation) {
        requireWrite(context);
        requireIdempotency(key);
        lockIdempotency(context, "reservation", key);
        String hash = requestHash("reservation", orderId, expected);
        IdempotencyRecord prior = idempotent(context, "reservation", key);
        if (prior != null) { requireSamePayload(prior, hash); return loadReservation(context, uuid(prior.resourceId()), false); }
        OrderData order = loadOrder(context, uuid(orderId), true);
        IdempotencyRecord afterLock = idempotent(context, "reservation", key);
        if (afterLock != null) { requireSamePayload(afterLock, hash); return loadReservation(context, uuid(afterLock.resourceId()), false); }
        if (!order.status().equals("CONFIRMED")) throw error("FULFILLMENT_CANDIDATE_NOT_ELIGIBLE", false);
        if (order.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        UUID existing = jdbc.query("select id from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and sales_order_id=? and status in ('PENDING','RESERVED') order by created_at,id limit 1 for update",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null, tenant(context), workspace(context), order.id());
        // SALES_ORDER_CONFIRMED is also consumed asynchronously. If that
        // consumer won the race, the existing reservation is the idempotent
        // result and must not become a duplicate or a false conflict.
        if (existing != null) return loadReservation(context, existing, false);
        List<LineData> lines = lines(context, order.id());
        if (lines.isEmpty()) throw error("FULFILLMENT_CANDIDATE_NOT_ELIGIBLE", false);
        UUID selectedWarehouseId = selectedWarehouseId(order);
        List<WarehouseOperationsService.ProposalLine> proposals = lines.stream()
                .map(line -> proposal(context, line, true, selectedWarehouseId)).toList();
        boolean complete = proposals.stream().allMatch(WarehouseOperationsService.ProposalLine::complete);
        UUID reservationId = UUID.randomUUID();
        InventoryReservation aggregate = InventoryReservation.rehydrate(reservationId.toString(),
                InventoryReservationStatus.PENDING, proposals.stream().flatMap(value -> value.allocations().stream())
                        .map(value -> new InventoryReservation.Allocation(value.lotId(), value.quantity())).toList());
        if (complete) aggregate.reserve(aggregate.allocations());
        else aggregate.recordShortage();
        Timestamp created = now();
        String status = aggregate.status().name();
        checkUpdated(jdbc.update("insert into warehouse.inventory_reservation(id,tenant_id,workspace_id,sales_order_id,order_number,client_account_id,status,created_at,updated_at,reserved_at,expires_at) values (?,?,?,?,?,?,?, ?,?,?,?)",
                reservationId, tenant(context), workspace(context), order.id(), order.number(), order.clientAccountId(), status, created, created,
                complete ? created : null, Timestamp.from(created.toInstant().plusSeconds(7200))), "reservation insert");
        for (WarehouseOperationsService.ProposalLine proposal : proposals) {
            UUID lineId = UUID.randomUUID();
            checkUpdated(jdbc.update("insert into warehouse.inventory_reservation_line(id,reservation_id,catalog_item_id,sku_id,requested_quantity,unit,shortage_quantity) values (?,?,?,?,?,?,?)",
                    lineId, reservationId, proposal.catalogItemId(), uuidNullable(proposal.skuId()), proposal.requested(), proposal.unit(), proposal.shortage()), "reservation line insert");
            if (!complete) {
                if (proposal.shortage().signum() > 0) checkUpdated(jdbc.update("insert into warehouse.reservation_shortage(id,reservation_line_id,quantity,reason) values (?,?,?,?)",
                        UUID.randomUUID(), lineId, proposal.shortage(), "Insufficient available stock"), "reservation shortage insert");
                continue;
            }
            for (WarehouseOperationsService.AllocationView allocation : proposal.allocations()) {
                WarehouseOperationsService.LotSummary lot = loadLot(context, uuid(allocation.lotId()), true);
                InventoryLot lotAggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                        InventoryLotStatus.valueOf(lot.status()));
                if (!lot.unit().equals(allocation.unit())) throw error("INVENTORY_SHORTAGE", false);
                try { lotAggregate.reserve(allocation.quantity()); }
                catch (IllegalStateException exception) { throw error("INVENTORY_SHORTAGE", false); }
                checkUpdated(jdbc.update("insert into warehouse.inventory_reservation_allocation(id,reservation_line_id,lot_id,quantity,unit,expiration_date) values (?,?,?,?,?,?)",
                        UUID.randomUUID(), lineId, uuid(allocation.lotId()), allocation.quantity(), allocation.unit(), allocation.expirationDate()), "allocation insert");
                checkUpdated(jdbc.update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity+?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and stock_quantity-reserved_quantity>=?",
                        allocation.quantity(), tenant(context), workspace(context), uuid(allocation.lotId()), lot.version(), allocation.quantity()), "lot reservation update", "INVENTORY_SHORTAGE");
                insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), uuid(allocation.lotId()), lot.catalogItemId(), uuidNullable(lot.skuId()),
                        "RESERVATION", allocation.quantity(), allocation.unit(), lot.onHand(), lot.onHand(), lot.reserved(), lot.reserved().add(allocation.quantity()),
                        "Sales order " + order.number(), correlation, created);
            }
        }
        appendEvent(context, reservationId, complete ? "warehouse.reservation.created" : "warehouse.reservation.shortage", "reservation");
        saveIdempotency(context, "reservation", key, hash, reservationId.toString());
        WarehouseOperationsService.ReservationDetail result = loadReservation(context, reservationId, false);
        return result;
    }

    public WarehouseOperationsService.ReservationDetail release(CurrentAccessContext context, String reservationId, long expected,
                                                                  String key, String reason, String correlation, boolean expiry) {
        requireWrite(context);
        requireIdempotency(key);
        String normalizedReason = bounded(reason, "reason", 2000);
        String operation = expiry ? "reservation-expiry" : "reservation-release";
        lockIdempotency(context, operation, key);
        String hash = requestHash(operation, reservationId, expected, normalizedReason);
        IdempotencyRecord prior = idempotent(context, operation, key);
        if (prior != null) { requireSamePayload(prior, hash); return loadReservation(context, uuid(prior.resourceId()), false); }
        WarehouseOperationsService.ReservationDetail reservation = loadReservation(context, uuid(reservationId), true);
        if (!reservation.status().equals("RESERVED")) throw error("INVENTORY_RESERVATION_TRANSITION_INVALID", false);
        if (reservation.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        Timestamp occurred = now();
        List<WarehouseOperationsService.AllocationView> reservationAllocations = allocations(tenant(context), workspace(context), uuid(reservationId));
        InventoryReservation aggregate = InventoryReservation.rehydrate(reservation.id(),
                InventoryReservationStatus.RESERVED, reservationAllocations.stream()
                        .map(value -> new InventoryReservation.Allocation(value.lotId(), value.quantity())).toList());
        if (expiry) aggregate.expire(); else aggregate.release();
        for (WarehouseOperationsService.AllocationView allocation : reservationAllocations) {
            WarehouseOperationsService.LotSummary lot = loadLot(context, uuid(allocation.lotId()), true);
            InventoryLot lotAggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                    InventoryLotStatus.valueOf(lot.status()));
            try { lotAggregate.releaseReservation(allocation.quantity()); }
            catch (IllegalStateException exception) { throw error("CONCURRENCY_CONFLICT", false); }
            checkUpdated(jdbc.update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                    allocation.quantity(), tenant(context), workspace(context), uuid(allocation.lotId()), lot.version(), allocation.quantity()), "reservation release lot update", "CONCURRENCY_CONFLICT");
            insertMovement(context, uuid(lot.warehouseId()), uuid(lot.zoneId()), uuid(allocation.lotId()), lot.catalogItemId(), uuidNullable(lot.skuId()),
                    expiry ? "RESERVATION_EXPIRATION" : "RESERVATION_RELEASE", allocation.quantity(), allocation.unit(), lot.onHand(), lot.onHand(),
                    lot.reserved(), lot.reserved().subtract(allocation.quantity()), normalizedReason, correlation, occurred);
        }
        String nextStatus = expiry ? "EXPIRED" : "RELEASED";
        checkUpdated(jdbc.update("update warehouse.inventory_reservation set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and status='RESERVED'",
                nextStatus, occurred, tenant(context), workspace(context), uuid(reservationId), expected), "reservation transition", "CONCURRENCY_CONFLICT");
        appendEvent(context, uuid(reservationId), expiry ? "warehouse.reservation.expired" : "warehouse.reservation.released", "reservation", "ACTIVE", occurred);
        saveIdempotency(context, operation, key, hash, reservationId);
        return loadReservation(context, uuid(reservationId), false);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.ReservationSummary> reservations(CurrentAccessContext context,
                                                                                                       String status, int page, int size) {
        requireRead(context);
        pageCheck(page, size);
        String predicate = "where tenant_id=? and workspace_id=?";
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (status != null && !status.isBlank()) { predicate += " and status=?"; args.add(enumValue(status, "status", "PENDING", "RESERVED", "SHORTAGE", "RELEASED", "EXPIRED", "CANCELLED", "CONSUMED")); }
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<WarehouseOperationsService.ReservationSummary> items = jdbc.query("select id,sales_order_id,order_number,status,created_at,reserved_at,expires_at,version from warehouse.inventory_reservation "
                        + predicate + " order by created_at desc,id desc limit ? offset ?", (rs, row) -> new WarehouseOperationsService.ReservationSummary(
                        rs.getObject("id").toString(), rs.getObject("sales_order_id").toString(), rs.getString("order_number"), rs.getString("status"),
                        instant(rs, "created_at"), instantNullable(rs, "reserved_at"), instant(rs, "expires_at"), rs.getLong("version")), pageArgs.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size, count("select count(*) from warehouse.inventory_reservation " + predicate, args.toArray()));
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.ReservationDetail reservation(CurrentAccessContext context, String id) {
        requireRead(context);
        return loadReservation(context, uuid(id), false);
    }

    public void expireReservations() {
        List<ScopeId> expired = jdbc.query("select tenant_id,workspace_id,id from warehouse.inventory_reservation where status='RESERVED' and expires_at<current_timestamp order by expires_at,id limit 100 for update skip locked",
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
        List<WarehouseOperationsService.AllocationView> reservationAllocations = allocations(candidate.tenantId(), candidate.workspaceId(), reservationId(reservation));
        InventoryReservation aggregate = InventoryReservation.rehydrate(reservation.id(),
                InventoryReservationStatus.RESERVED, reservationAllocations.stream()
                        .map(value -> new InventoryReservation.Allocation(value.lotId(), value.quantity())).toList());
        aggregate.expire();
        for (WarehouseOperationsService.AllocationView allocation : reservationAllocations) {
            WarehouseOperationsService.LotSummary lot = loadLot(candidate.tenantId(), candidate.workspaceId(), uuid(allocation.lotId()), true);
            InventoryLot lotAggregate = InventoryLot.rehydrate(lot.id(), lot.onHand(), lot.reserved(), lot.unit(),
                    InventoryLotStatus.valueOf(lot.status()));
            try { lotAggregate.releaseReservation(allocation.quantity()); }
            catch (IllegalStateException exception) { throw error("CONCURRENCY_CONFLICT", false); }
            checkUpdated(jdbc.update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                    allocation.quantity(), candidate.tenantId(), candidate.workspaceId(), uuid(allocation.lotId()), lot.version(), allocation.quantity()), "expiry lot update", "CONCURRENCY_CONFLICT");
            insertMovement(candidate.tenantId(), candidate.workspaceId(), uuid(lot.warehouseId()), uuid(lot.zoneId()), uuid(allocation.lotId()), lot.catalogItemId(), uuidNullable(lot.skuId()),
                    "RESERVATION_EXPIRATION", allocation.quantity(), allocation.unit(), lot.onHand(), lot.onHand(), lot.reserved(), lot.reserved().subtract(allocation.quantity()),
                    "Reservation expired", null, "reservation-expiry-" + candidate.id(), occurred);
        }
        checkUpdated(jdbc.update("update warehouse.inventory_reservation set status='EXPIRED',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                occurred, candidate.tenantId(), candidate.workspaceId(), candidate.id()), "expiry reservation transition", "CONCURRENCY_CONFLICT");
        appendEvent(candidate.tenantId(), candidate.workspaceId(), candidate.id(), "warehouse.reservation.expired", "reservation", "ACTIVE", occurred);
    }

    private UUID reservationId(WarehouseOperationsService.ReservationDetail reservation) { return uuid(reservation.id()); }
    private void requireRead(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ); }
    private void requireWrite(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantmanagement.domain.model.access.Permission.WAREHOUSE_WRITE); }
    private void requireFulfillmentRead(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantmanagement.domain.model.access.Permission.FULFILLMENT_READ); }
}
