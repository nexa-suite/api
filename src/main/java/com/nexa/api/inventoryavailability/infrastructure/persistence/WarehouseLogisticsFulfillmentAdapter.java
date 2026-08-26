package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService.WarehouseException;
import com.nexa.api.inventoryavailability.application.port.WarehouseLogisticsFulfillmentPort;
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
import java.util.List;
import java.util.UUID;

/** Owns the Warehouse side of the Logistics reservation handoff. */
@Repository
@Profile("!test")
public class WarehouseLogisticsFulfillmentAdapter implements WarehouseLogisticsFulfillmentPort {
    private final JdbcTemplate jdbc;
    private final ChangeEventPersistencePort changeFeed;

    public WarehouseLogisticsFulfillmentAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed) {
        this.jdbc = jdbc;
        this.changeFeed = changeFeed;
    }

    @Override
    public DispatchReservationSnapshot loadReservedReservation(String tenantId, String workspaceId,
                                                                String reservationId, long expectedVersion, Instant now) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), reservation = uuid(reservationId);
        return jdbc.query("select r.id,r.sales_order_id,r.order_number,r.client_account_id,r.status,r.expires_at,r.version,o.delivery_snapshot,"
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
                        + "from warehouse.inventory_reservation r join sales.sales_order o on o.tenant_id=r.tenant_id "
                        + "and o.workspace_id=r.workspace_id and o.id=r.sales_order_id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? for update",
                rs -> {
                    if (!rs.next()) throw error("RESOURCE_NOT_FOUND", true);
                    Instant expires = rs.getTimestamp("expires_at").toInstant();
                    String status = rs.getString("status");
                    long version = rs.getLong("version");
                    if (version != expectedVersion) throw error("CONCURRENCY_CONFLICT", false);
                    if (!"RESERVED".equals(status) || !expires.isAfter(now)) throw error("RESERVATION_NOT_READY", false);
                    return new DispatchReservationSnapshot(reservation, rs.getObject("sales_order_id", UUID.class),
                            rs.getString("order_number"), rs.getObject("client_account_id", UUID.class), status,
                            expires, version, rs.getString("delivery_snapshot"), rs.getBigDecimal("temperature_min"),
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
        return jdbc.queryForObject("select count(*) from warehouse.inventory_reservation where tenant_id=? and workspace_id=? "
                        + "and status='RESERVED' and expires_at>?", Long.class, uuid(tenantId), uuid(workspaceId), timestamp(now));
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
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? order by a.lot_id for update of l",
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
    private record ReservationLock(UUID id, String status, Instant expiresAt, long version) { }
    private record Allocation(UUID lotId, BigDecimal quantity, String unit, UUID warehouseId, UUID zoneId,
                              String catalogItemId, UUID skuId, BigDecimal stock, BigDecimal reserved, String status, long version) { }
}
