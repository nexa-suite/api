package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL regression proof for the shared inventory-lot lock order. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WarehouseLotLockOrderingIT extends NexaWorkflowIntegrationSupport {

    @Autowired
    private DataSource dataSource;

    @Test
    void legacyReservationAndPhysicalAllocationLockPathsDoNotDeadlockAcrossWarehouses() throws Exception {
        assertLockPathsDoNotDeadlock(legacyReservationLockSql(), physicalAllocationLockSql());
    }

    @Test
    void transferAndPhysicalAllocationLockPathsDoNotDeadlockAcrossWarehouses() throws Exception {
        assertLockPathsDoNotDeadlock(transferLockSql(), physicalAllocationLockSql());
    }

    private void assertLockPathsDoNotDeadlock(String firstSql, String secondSql) throws Exception {
        ensureCommercialInventory();
        ensureCommercialInventory();

        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID sku = jdbc.queryForObject("select id from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and legacy_catalog_item_id=? and status='ACTIVE'",
                UUID.class, tenant, workspace, "CAT-0002");
        List<LotRef> lots = jdbc.query("select distinct on (l.warehouse_id) l.id,l.sku_id,l.warehouse_id,l.expiration_date,l.received_at "
                        + "from warehouse.inventory_lot l where l.tenant_id=? and l.workspace_id=? and l.sku_id=? "
                        + "and l.status='AVAILABLE' and l.stock_quantity>l.reserved_quantity and l.expiration_date>current_date "
                        + "order by l.warehouse_id,l.expiration_date,l.received_at,l.id",
                (rs, row) -> new LotRef(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class), rs.getObject("expiration_date", LocalDate.class),
                        rs.getTimestamp("received_at").toInstant()), tenant, workspace, sku);
        assertThat(lots).as("the cross-route lock test needs two warehouses").hasSizeGreaterThanOrEqualTo(2);
        List<LotRef> selected = lots.subList(0, 2);

        // Make FEFO order intentionally differ from warehouse order. Business
        // selection may remain FEFO, but every route must lock canonically.
        LotRef canonicalFirst = selected.get(0);
        LotRef canonicalSecond = selected.get(1);
        jdbc.update("update warehouse.inventory_lot set expiration_date=? where tenant_id=? and workspace_id=? and id=?",
                LocalDate.now().plusDays(120), tenant, workspace, canonicalFirst.id());
        jdbc.update("update warehouse.inventory_lot set expiration_date=? where tenant_id=? and workspace_id=? and id=?",
                LocalDate.now().plusDays(30), tenant, workspace, canonicalSecond.id());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<LockResult> reservation = executor.submit(() -> lockLots(
                    firstSql, tenant, workspace, sku, selected, ready, start));
            Future<LockResult> physical = executor.submit(() -> lockLots(
                    secondSql, tenant, workspace, sku, selected, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            LockResult reservationResult = reservation.get(10, TimeUnit.SECONDS);
            LockResult physicalResult = physical.get(10, TimeUnit.SECONDS);
            assertThat(reservationResult.lockedIds()).containsExactlyElementsOf(physicalResult.lockedIds());
            assertThat(reservationResult.lockedIds()).containsExactly(canonicalFirst.id(), canonicalSecond.id());
        } finally {
            jdbc.update("update warehouse.inventory_lot set expiration_date=? where tenant_id=? and workspace_id=? and id=?",
                    canonicalFirst.expirationDate(), tenant, workspace, canonicalFirst.id());
            jdbc.update("update warehouse.inventory_lot set expiration_date=? where tenant_id=? and workspace_id=? and id=?",
                    canonicalSecond.expirationDate(), tenant, workspace, canonicalSecond.id());
        }
    }

    private LockResult lockLots(String sql, UUID tenant, UUID workspace, UUID sku, List<LotRef> lots,
                                CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement setup = connection.createStatement()) {
                setup.execute("set local lock_timeout='3s'");
            }
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock test did not start");

            List<UUID> lockedIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, tenant);
                statement.setObject(2, workspace);
                statement.setObject(3, sku);
                statement.setObject(4, lots.get(0).id());
                statement.setObject(5, lots.get(1).id());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) lockedIds.add(result.getObject(1, UUID.class));
                }
            }
            try (Statement hold = connection.createStatement()) {
                hold.execute("select pg_sleep(0.2)");
            }
            connection.commit();
            return new LockResult(lockedIds);
        }
    }

    private static String legacyReservationLockSql() {
        return "select l.id from warehouse.inventory_lot l "
                + "where l.tenant_id=? and l.workspace_id=? and l.sku_id=? and l.id in (?,?) "
                + "order by " + WarehouseLotLockOrder.inventoryLot("l") + " for update of l";
    }

    private static String transferLockSql() {
        return "select l.id from warehouse.inventory_lot l "
                + "where l.tenant_id=? and l.workspace_id=? and l.sku_id=? and l.id in (?,?) "
                + "order by " + WarehouseLotLockOrder.inventoryLot("l") + " for update of l";
    }

    private static String physicalAllocationLockSql() {
        return "select l.id from warehouse.inventory_lot l "
                + "join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id "
                + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id "
                + "and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                + "where l.tenant_id=? and l.workspace_id=? and l.sku_id=? and l.id in (?,?) "
                + "and l.status='AVAILABLE' and l.stock_quantity-l.reserved_quantity>0 "
                + "order by " + WarehouseLotLockOrder.inventoryLot("l") + " for update of l,w,z";
    }

    private record LotRef(UUID id, UUID skuId, UUID warehouseId, LocalDate expirationDate, Instant receivedAt) { }
    private record LockResult(List<UUID> lockedIds) { }
}
