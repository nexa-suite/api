package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService.WarehouseException;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort.ReservationOrderReference;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLot;
import com.nexa.api.inventoryavailability.domain.model.inventorylot.InventoryLotStatus;
import com.nexa.api.inventoryavailability.domain.model.inventoryreservation.InventoryReservation;
import com.nexa.api.inventoryavailability.domain.model.inventoryreservation.InventoryReservationStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Owns the Warehouse side of the Logistics reservation handoff. */
@Repository
@Profile("!test")
public class WarehouseLogisticsFulfillmentAdapter implements WarehouseLogisticsFulfillmentPort {
    private static final int VISIBILITY_QUERY_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final ChangeEventPersistencePort changeFeed;
    private final InventoryCommercialSource commercialSource;

    public WarehouseLogisticsFulfillmentAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                                InventoryCommercialSource commercialSource) {
        this.jdbc = jdbc;
        this.changeFeed = changeFeed;
        this.commercialSource = commercialSource;
    }

    @Override
    public DispatchReservationSnapshot loadReservedReservation(String tenantId, String workspaceId,
                                                                String reservationId, long expectedVersion, Instant now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), reservation = uuid(reservationId);
        UUID salesOrderId = jdbc.query("select sales_order_id from warehouse.inventory_reservation "
                        + "where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> rs.getObject(1, UUID.class), tenant, workspace, reservation).stream().findFirst()
                .orElseThrow(() -> error("RESOURCE_NOT_FOUND", true));
        InventoryCommercialSource.Snapshot order = commercialSource.claimCandidate(tenant, workspace, salesOrderId)
                .orElseThrow(() -> error("RESOURCE_NOT_FOUND", true));
        return jdbc.query("select r.id,r.sales_order_id,r.status,r.expires_at,r.version,"
                        + "(select min(z.temperature_min) from warehouse.inventory_reservation_allocation a "
                        + "join warehouse.inventory_reservation_line rl on rl.id=a.reservation_line_id "
                        + "join warehouse.inventory_lot l on l.id=a.lot_id and l.tenant_id=r.tenant_id and l.workspace_id=r.workspace_id "
                        + "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "where rl.reservation_id=r.id) temperature_min,"
                        + "(select max(z.temperature_max) from warehouse.inventory_reservation_allocation a "
                        + "join warehouse.inventory_reservation_line rl on rl.id=a.reservation_line_id "
                        + "join warehouse.inventory_lot l on l.id=a.lot_id and l.tenant_id=r.tenant_id and l.workspace_id=r.workspace_id "
                        + "join warehouse.storage_zone z on z.id=l.zone_id and z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                        + "where rl.reservation_id=r.id) temperature_max "
                        + "from warehouse.inventory_reservation r "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? for update",
                rs -> {
                    if (!rs.next()) throw error("RESOURCE_NOT_FOUND", true);
                    Instant expires = rs.getTimestamp("expires_at").toInstant();
                    String status = rs.getString("status");
                    long version = rs.getLong("version");
                    if (version != expectedVersion) throw error("CONCURRENCY_CONFLICT", false);
                    if (!"RESERVED".equals(status) || !expires.isAfter(now)) throw error("RESERVATION_NOT_READY", false);
                    return new DispatchReservationSnapshot(reservation, salesOrderId,
                            order.number(), order.clientAccountId(), status,
                            expires, version, order.destinationSnapshot(), rs.getBigDecimal("temperature_min"),
                            rs.getBigDecimal("temperature_max"), rs.getBigDecimal("temperature_min") == null ? null : "CELSIUS", "UNKNOWN");
                }, tenant, workspace, reservation);
    }

    @Override
    public void ensureReservationReady(String tenantId, String workspaceId, String reservationId, Instant now) {
        ReservationLock lock = lockReservation(uuid(tenantId), uuid(workspaceId), uuid(reservationId));
        if (lock == null) throw error("RESERVATION_NOT_FOUND", true);
        // A reprogrammed route may restart after an incident once outbound stock
        // was already consumed by the first route start. Never reserve it again.
        if ("CONSUMED".equals(lock.status())) return;
        if (!"RESERVED".equals(lock.status()) || !lock.expiresAt().isAfter(now)) {
            throw error("RESERVATION_NOT_READY", false);
        }
    }

    @Override
    public void consumeReservation(String tenantId, String workspaceId, String reservationId,
                                   String actorMembershipId, String correlationId, Instant now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), reservation = uuid(reservationId), actor = uuid(actorMembershipId);
        ReservationLock lock = lockReservation(tenant, workspace, reservation);
        if (lock == null) throw error("RESERVATION_NOT_FOUND", true);
        if ("CONSUMED".equals(lock.status())) return;
        if (!"RESERVED".equals(lock.status()) || !lock.expiresAt().isAfter(now)) throw error("RESERVATION_NOT_READY", false);
        List<Allocation> allocations = allocations(tenant, workspace, reservation);
        if (allocations.isEmpty()) throw error("RESERVATION_NOT_READY", false);
        InventoryReservation aggregate = InventoryReservation.rehydrate(reservationId, InventoryReservationStatus.RESERVED,
                allocations.stream().map(value -> new InventoryReservation.Allocation(value.lotId().toString(), value.quantity())).toList());
        aggregate.consume();
        for (Allocation allocation : allocations) consumeLot(tenant, workspace, allocation, actor, correlationId, now);
        check(jdbc.update("update warehouse.inventory_reservation set status='CONSUMED',updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and status='RESERVED' and version=?",
                timestamp(now), tenant, workspace, reservation, lock.version()), "CONCURRENCY_CONFLICT");
        inventoryEvent(tenant, workspace, reservation, actor, "RESERVATION_CONSUMED", correlationId, now);
        changeFeed.append(tenant.toString(), workspace.toString(), null, "reservation", reservation.toString(),
                "warehouse.reservation.consumed", "CONSUMED", now.toEpochMilli(), false);
    }

    @Override
    public void releaseReservation(String tenantId, String workspaceId, String reservationId,
                                   String actorMembershipId, String correlationId, String reason, Instant now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), reservation = uuid(reservationId), actor = uuid(actorMembershipId);
        ReservationLock lock = lockReservation(tenant, workspace, reservation);
        if (lock == null) throw error("RESERVATION_NOT_FOUND", true);
        if ("RELEASED".equals(lock.status()) || "EXPIRED".equals(lock.status())) return;
        if (!"RESERVED".equals(lock.status())) throw error("RESERVATION_NOT_READY", false);
        List<Allocation> allocations = allocations(tenant, workspace, reservation);
        InventoryReservation aggregate = InventoryReservation.rehydrate(reservationId, InventoryReservationStatus.RESERVED,
                allocations.stream().map(value -> new InventoryReservation.Allocation(value.lotId().toString(), value.quantity())).toList());
        aggregate.release();
        for (Allocation allocation : allocations) releaseLot(tenant, workspace, allocation, actor, correlationId, reason, now);
        check(jdbc.update("update warehouse.inventory_reservation set status='RELEASED',updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and status='RESERVED' and version=?",
                timestamp(now), tenant, workspace, reservation, lock.version()), "CONCURRENCY_CONFLICT");
        inventoryEvent(tenant, workspace, reservation, actor, "RESERVATION_RELEASED", correlationId, now);
        changeFeed.append(tenant.toString(), workspace.toString(), null, "reservation", reservation.toString(),
                "warehouse.reservation.released", "RELEASED", now.toEpochMilli(), false);
    }

    @Override
    public long countReadyReservations(String tenantId, String workspaceId, Instant now) {
        return jdbc.queryForObject("select count(*) from warehouse.inventory_reservation where tenant_id=? "
                        + "and workspace_id=? and status='RESERVED' and expires_at>?", Long.class,
                uuid(tenantId), uuid(workspaceId), timestamp(now));
    }

    @Override
    public long countReadyReservations(String tenantId, String workspaceId, Set<UUID> grantedWarehouseIds,
                                       Instant now) {
        Set<UUID> grants = normalized(grantedWarehouseIds);
        if (grants.isEmpty()) return 0;
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        List<ReservationCandidate> candidates = jdbc.query(
                "select r.id,r.sales_order_id from warehouse.inventory_reservation r "
                        + "where r.tenant_id=? and r.workspace_id=? and r.status='RESERVED' and r.expires_at>?",
                (rs, row) -> new ReservationCandidate(rs.getObject("id", UUID.class),
                        rs.getObject("sales_order_id", UUID.class)),
                tenant, workspace, timestamp(now));
        return findVisibleReservationReferences(tenant, workspace, candidates, grants, Set.of("BACKED")).size();
    }

    @Override
    public Set<ReservationOrderReference> visibleReservationReferences(String tenantId, String workspaceId,
                                                                       Set<ReservationOrderReference> reservationReferences,
                                                                       Set<UUID> grantedWarehouseIds) {
        Set<ReservationOrderReference> requestedReferences = reservationReferences == null
                ? Set.of() : reservationReferences.stream().filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> grants = normalized(grantedWarehouseIds);
        if (requestedReferences.isEmpty() || grants.isEmpty()) return Set.of();

        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        List<UUID> sortedIds = requestedReferences.stream().map(ReservationOrderReference::reservationId)
                .distinct().sorted().toList();
        List<ReservationCandidate> candidates = new ArrayList<>();
        for (int start = 0; start < sortedIds.size(); start += VISIBILITY_QUERY_BATCH_SIZE) {
            List<UUID> idBatch = sortedIds.subList(start,
                    Math.min(start + VISIBILITY_QUERY_BATCH_SIZE, sortedIds.size()));
            String idParameters = placeholders(idBatch.size());
            List<Object> args = new ArrayList<>(List.of(tenant, workspace));
            args.addAll(idBatch);
            List<ReservationCandidate> found = jdbc.query(
                    "select r.id,r.sales_order_id from warehouse.inventory_reservation r "
                            + "where r.tenant_id=? and r.workspace_id=? and r.id in (" + idParameters + ")",
                    (rs, row) -> new ReservationCandidate(rs.getObject("id", UUID.class),
                            rs.getObject("sales_order_id", UUID.class)), args.toArray());
            found.stream().filter(candidate -> candidate.salesOrderId() != null)
                    .filter(candidate -> requestedReferences.contains(
                            new ReservationOrderReference(candidate.id(), candidate.salesOrderId())))
                    .forEach(candidates::add);
        }
        return findVisibleReservationReferences(tenant, workspace, candidates, grants,
                Set.of("BACKED", "CONSUMED"));
    }

    private Set<ReservationOrderReference> findVisibleReservationReferences(UUID tenant, UUID workspace,
                                                                            List<ReservationCandidate> candidates,
                                                                            Set<UUID> grantedWarehouseIds,
                                                                            Set<String> backingStatuses) {
        if (candidates.isEmpty() || grantedWarehouseIds.isEmpty()) return Set.of();

        Map<UUID, Optional<UUID>> commitmentByOrder = new LinkedHashMap<>();
        Map<ReservationOrderReference, UUID> commitmentByReservation = new LinkedHashMap<>();
        for (ReservationCandidate candidate : candidates) {
            if (candidate.salesOrderId() == null) continue;
            Optional<UUID> commitmentId = commitmentByOrder.computeIfAbsent(candidate.salesOrderId(), orderId ->
                    commercialSource.findCandidate(tenant, workspace, orderId)
                            .map(InventoryCommercialSource.Snapshot::commercialCommitmentId));
            commitmentId.filter(Objects::nonNull)
                    .ifPresent(id -> commitmentByReservation.put(
                            new ReservationOrderReference(candidate.id(), candidate.salesOrderId()), id));
        }
        Set<UUID> commitmentIds = Set.copyOf(commitmentByReservation.values());
        if (commitmentIds.isEmpty()) return Set.of();

        List<UUID> sortedWarehouses = grantedWarehouseIds.stream().sorted().toList();
        String statusParameters = placeholders(backingStatuses.size());
        String warehouseParameters = placeholders(sortedWarehouses.size());
        List<UUID> sortedCommitments = commitmentIds.stream().sorted().toList();
        Set<UUID> fullyBackedCommitments = new LinkedHashSet<>();
        for (int start = 0; start < sortedCommitments.size(); start += VISIBILITY_QUERY_BATCH_SIZE) {
            List<UUID> commitmentBatch = sortedCommitments.subList(start,
                    Math.min(start + VISIBILITY_QUERY_BATCH_SIZE, sortedCommitments.size()));
            List<Object> args = new ArrayList<>(List.of(tenant, workspace));
            args.addAll(commitmentBatch);
            args.addAll(backingStatuses.stream().sorted().toList());
            args.addAll(sortedWarehouses);
            fullyBackedCommitments.addAll(jdbc.query(
                    "select b.commercial_commitment_id from warehouse.inventory_backing b "
                            + "where b.tenant_id=? and b.workspace_id=? and b.commercial_commitment_id in ("
                            + placeholders(commitmentBatch.size()) + ") and b.status in (" + statusParameters + ") "
                            + "and exists (select 1 from warehouse.inventory_backing_line bl "
                            + "where bl.tenant_id=b.tenant_id and bl.workspace_id=b.workspace_id and bl.backing_id=b.id) "
                            + "and not exists (select 1 from warehouse.inventory_backing_line bl "
                            + "where bl.tenant_id=b.tenant_id and bl.workspace_id=b.workspace_id and bl.backing_id=b.id "
                            + "and (bl.backed_quantity<>bl.requested_quantity or bl.backed_quantity<>coalesce(("
                            + "select sum(bp.quantity) from warehouse.inventory_backing_position bp "
                            + "where bp.tenant_id=bl.tenant_id and bp.workspace_id=bl.workspace_id "
                            + "and bp.backing_line_id=bl.id),0))) "
                            + "and not exists (select 1 from warehouse.inventory_backing_line bl "
                            + "join warehouse.inventory_backing_position bp on bp.tenant_id=bl.tenant_id "
                            + "and bp.workspace_id=bl.workspace_id and bp.backing_line_id=bl.id "
                            + "where bl.tenant_id=b.tenant_id and bl.workspace_id=b.workspace_id and bl.backing_id=b.id "
                            + "and bp.warehouse_id not in (" + warehouseParameters + "))",
                    (rs, row) -> rs.getObject("commercial_commitment_id", UUID.class), args.toArray()));
        }
        if (fullyBackedCommitments.isEmpty()) return Set.of();
        Set<ReservationOrderReference> visible = new LinkedHashSet<>();
        commitmentByReservation.forEach((reservationId, commitmentId) -> {
            if (fullyBackedCommitments.contains(commitmentId)) visible.add(reservationId);
        });
        return java.util.Collections.unmodifiableSet(visible);
    }

    private static Set<UUID> normalized(Set<UUID> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private ReservationLock lockReservation(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select id,status,expires_at,version from warehouse.inventory_reservation "
                        + "where tenant_id=? and workspace_id=? and id=? for update",
                rs -> rs.next() ? new ReservationLock(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getLong("version")) : null,
                tenant, workspace, id);
    }

    private List<Allocation> allocations(UUID tenant, UUID workspace, UUID reservation) {
        return jdbc.query("select a.lot_id,a.quantity,a.unit,l.warehouse_id,l.zone_id,l.catalog_item_id,l.sku_id,"
                        + "l.stock_quantity,l.reserved_quantity,l.status,l.version from warehouse.inventory_reservation_allocation a "
                        + "join warehouse.inventory_reservation_line rl on rl.id=a.reservation_line_id "
                        + "join warehouse.inventory_reservation r on r.id=rl.reservation_id "
                        + "join warehouse.inventory_lot l on l.id=a.lot_id and l.tenant_id=r.tenant_id and l.workspace_id=r.workspace_id "
                        // Keep the lot lock order identical to reservation,
                        // backing and physical-allocation paths.
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? order by "
                        + WarehouseLotLockOrder.inventoryLot("l") + " for update of l",
                (rs, row) -> new Allocation(rs.getObject("lot_id", UUID.class), rs.getBigDecimal("quantity"),
                        rs.getString("unit"), rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getObject("sku_id", UUID.class), rs.getBigDecimal("stock_quantity"),
                        rs.getBigDecimal("reserved_quantity"), rs.getString("status"), rs.getLong("version")),
                tenant, workspace, reservation);
    }

    private void consumeLot(UUID tenant, UUID workspace, Allocation a, UUID actor, String correlation, Instant now) {
        InventoryLot aggregate = InventoryLot.rehydrate(a.lotId().toString(), a.stock(), a.reserved(), a.unit(),
                InventoryLotStatus.valueOf(a.status()));
        try { aggregate.consume(a.quantity()); }
        catch (IllegalStateException exception) { throw error("INVENTORY_SHORTAGE", false); }
        check(jdbc.update("update warehouse.inventory_lot set stock_quantity=stock_quantity-?,reserved_quantity=reserved_quantity-?,"
                        + "status=case when stock_quantity-?=0 then 'DEPLETED' else status end,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and stock_quantity>=? and reserved_quantity>=?",
                a.quantity(), a.quantity(), a.quantity(), tenant, workspace, a.lotId(), a.version(), a.quantity(), a.quantity()),
                "CONCURRENCY_CONFLICT");
        movement(tenant, workspace, a, "OUTBOUND_CONSUMPTION", a.quantity(), a.stock(), a.stock().subtract(a.quantity()),
                a.reserved(), a.reserved().subtract(a.quantity()), actor, correlation, "Dispatch operation", now);
        inventoryEvent(tenant, workspace, a.lotId(), actor, "OUTBOUND_CONSUMPTION", correlation, now);
    }

    private void releaseLot(UUID tenant, UUID workspace, Allocation a, UUID actor, String correlation,
                            String reason, Instant now) {
        InventoryLot aggregate = InventoryLot.rehydrate(a.lotId().toString(), a.stock(), a.reserved(), a.unit(),
                InventoryLotStatus.valueOf(a.status()));
        try { aggregate.releaseReservation(a.quantity()); }
        catch (IllegalStateException exception) { throw error("CONCURRENCY_CONFLICT", false); }
        check(jdbc.update("update warehouse.inventory_lot set reserved_quantity=reserved_quantity-?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=? and reserved_quantity>=?",
                a.quantity(), tenant, workspace, a.lotId(), a.version(), a.quantity()), "CONCURRENCY_CONFLICT");
        movement(tenant, workspace, a, "RESERVATION_RELEASE", a.quantity(), a.stock(), a.stock(), a.reserved(),
                a.reserved().subtract(a.quantity()), actor, correlation, reason, now);
        inventoryEvent(tenant, workspace, a.lotId(), actor, "RESERVATION_RELEASED", correlation, now);
    }

    private void movement(UUID tenant, UUID workspace, Allocation a, String type, BigDecimal quantity,
                          BigDecimal before, BigDecimal after, BigDecimal reservedBefore, BigDecimal reservedAfter,
                          UUID actor, String correlation, String reason, Instant now) {
        check(jdbc.update("insert into warehouse.stock_movement(id,tenant_id,workspace_id,warehouse_id,zone_id,lot_id,catalog_item_id,sku_id,"
                        + "movement_type,quantity,unit,quantity_before,quantity_after,reserved_before,reserved_after,reason,"
                        + "actor_membership_id,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, workspace, a.warehouseId(), a.zoneId(), a.lotId(), a.catalogItemId(), a.skuId(), type,
                quantity, a.unit(), before, after, reservedBefore, reservedAfter, reason, actor, correlation, timestamp(now)),
                "MOVEMENT_INSERT_FAILED");
    }

    private void inventoryEvent(UUID tenant, UUID workspace, UUID aggregate, UUID actor, String type, String correlation, Instant now) {
        check(jdbc.update("insert into warehouse.inventory_event(id,tenant_id,workspace_id,aggregate_id,event_type,occurred_at,actor_membership_id,correlation_id) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, workspace, aggregate, type, timestamp(now), actor, correlation), "INVENTORY_EVENT_INSERT_FAILED");
    }

    private static void check(int count, String code) { if (count != 1) throw error(code, false); }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException exception) { throw error("INVALID_REQUEST", false); } }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static WarehouseException error(String code, boolean notFound) { return new WarehouseException(code, notFound); }
    private record ReservationCandidate(UUID id, UUID salesOrderId) { }
    private record ReservationLock(UUID id, String status, Instant expiresAt, long version) { }
    private record Allocation(UUID lotId, BigDecimal quantity, String unit, UUID warehouseId, UUID zoneId,
                              String catalogItemId, UUID skuId, BigDecimal stock, BigDecimal reserved, String status, long version) { }
}
