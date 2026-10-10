package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.AdjustInventory;
import com.nexa.api.inventoryavailability.application.BlockLot;
import com.nexa.api.inventoryavailability.application.ConfigureWarehouse;
import com.nexa.api.inventoryavailability.application.ConfigureWarehouseZone;
import com.nexa.api.inventoryavailability.application.CycleCountInventory;
import com.nexa.api.inventoryavailability.application.ExpireReservation;
import com.nexa.api.inventoryavailability.application.ManageSafetyStock;
import com.nexa.api.inventoryavailability.application.MarkFulfillmentReady;
import com.nexa.api.inventoryavailability.application.PrepareFulfillment;
import com.nexa.api.inventoryavailability.application.QuarantineLot;
import com.nexa.api.inventoryavailability.application.QueryAvailability;
import com.nexa.api.inventoryavailability.application.ReceiveInventory;
import com.nexa.api.inventoryavailability.application.RegisterWaste;
import com.nexa.api.inventoryavailability.application.ReleaseReservation;
import com.nexa.api.inventoryavailability.application.ReserveInventory;
import com.nexa.api.inventoryavailability.application.RestoreLot;
import com.nexa.api.inventoryavailability.application.TransferInventory;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;

/** Builds Warehouse application and JDBC adapters on one caller-supplied Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantWarehouseOperationsCompositionFactory
        implements TenantWarehouseOperationsCompositionFactory {

    @Override
    public WarehouseOperationsService bindTo(JdbcTemplate tenantJdbc, Bindings bindings) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Routed Tenant JDBC session is required");
        Bindings verified = Objects.requireNonNull(bindings, "Tenant Warehouse bindings are required");
        WarehouseOperationalSettingsPort settings = new CentralSettingsSnapshot(
                verified.centralSettingsSnapshot());

        WarehouseConfigurationPersistenceAdapter configuration = new WarehouseConfigurationPersistenceAdapter(
                jdbc, verified.changeFeed(), verified.sellableSkus(), null, settings,
                verified.commercialSource(), verified.fulfillmentSource(), verified.verifiedWarehouseAccess());
        WarehouseInventoryPersistenceAdapter inventory = new WarehouseInventoryPersistenceAdapter(
                jdbc, verified.changeFeed(), verified.sellableSkus(), null, settings,
                verified.commercialSource(), verified.fulfillmentSource(), verified.verifiedWarehouseAccess(),
                verified.businessEvidence());
        WarehouseReservationPersistenceAdapter reservations = new WarehouseReservationPersistenceAdapter(
                jdbc, verified.changeFeed(), verified.sellableSkus(), null, settings,
                verified.commercialSource(), verified.fulfillmentSource(), null, verified.verifiedWarehouseAccess());
        WarehouseDashboardQueryAdapter dashboard = new WarehouseDashboardQueryAdapter(jdbc);
        WarehouseTransferPersistenceAdapter transfers = new WarehouseTransferPersistenceAdapter(
                jdbc, verified.changeFeed(), verified.sellableSkus(), null, settings,
                verified.commercialSource(), verified.fulfillmentSource(), verified.verifiedWarehouseAccess());
        WarehouseSafetyStockPersistenceAdapter safetyStock = new WarehouseSafetyStockPersistenceAdapter(
                jdbc, verified.changeFeed(), verified.sellableSkus(), null, settings,
                verified.commercialSource(), verified.fulfillmentSource(), verified.verifiedWarehouseAccess());
        WarehouseOutboxPersistenceAdapter outbox = new WarehouseOutboxPersistenceAdapter(jdbc,
                verified.canonicalOutbox());

        return new WarehouseOperationsService(configuration, inventory, reservations, dashboard,
                new ConfigureWarehouse(configuration), new ConfigureWarehouseZone(configuration),
                new ReceiveInventory(inventory), new AdjustInventory(inventory), new RegisterWaste(inventory),
                new BlockLot(inventory), new QuarantineLot(inventory), new RestoreLot(inventory),
                new PrepareFulfillment(reservations), new ReserveInventory(reservations, outbox),
                new ReleaseReservation(reservations), new ExpireReservation(reservations),
                new MarkFulfillmentReady(dashboard), new QueryAvailability(inventory),
                new ManageSafetyStock(safetyStock), new TransferInventory(transfers),
                new CycleCountInventory(inventory));
    }

    private static final class CentralSettingsSnapshot implements WarehouseOperationalSettingsPort {
        private final Optional<Snapshot> snapshot;

        private CentralSettingsSnapshot(Optional<Snapshot> snapshot) {
            this.snapshot = Objects.requireNonNull(snapshot, "Central operational settings snapshot is required");
        }

        @Override
        public Optional<Snapshot> find(String tenantId, String workspaceId) {
            return snapshot;
        }

        @Override
        public int update(String tenantId, String workspaceId, String selectionPolicy,
                          LocalTime startsAt, LocalTime endsAt, long expectedVersion) {
            throw new WarehouseOperationsService.WarehouseException("FORBIDDEN", false);
        }
    }
}
