package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseConfigurationPersistencePort;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import com.nexa.api.inventoryavailability.application.port.WarehouseReservationPersistencePort;
import com.nexa.api.inventoryavailability.application.port.WarehouseDashboardQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Warehouse application boundary. Persistence and SQL stay behind WarehouseOperationsPort. */
@Service
@Profile("!test")
public class WarehouseOperationsApplicationService implements WarehouseOperationsService {
    private final WarehouseConfigurationPersistencePort configuration;
    private final WarehouseInventoryPersistencePort inventory;
    private final WarehouseReservationPersistencePort reservations;
    private final WarehouseDashboardQueryPort dashboard;
    private final ConfigureWarehouse configureWarehouse;
    private final ConfigureWarehouseZone configureWarehouseZone;
    private final ReceiveInventory receiveInventory;
    private final AdjustInventory adjustInventory;
    private final RegisterWaste registerWaste;
    private final BlockLot blockLot;
    private final QuarantineLot quarantineLot;
    private final RestoreLot restoreLot;
    private final PrepareFulfillment prepareFulfillment;
    private final ReserveInventory reserveInventory;
    private final ReleaseReservation releaseReservation;
    private final ExpireReservation expireReservation;
    private final MarkFulfillmentReady markFulfillmentReady;
    private final QueryAvailability queryAvailability;
    private final ManageSafetyStock manageSafetyStock;
    private final TransferInventory transferInventory;
    private final CycleCountInventory cycleCountInventory;

    public WarehouseOperationsApplicationService(WarehouseConfigurationPersistencePort configuration,
                                      WarehouseInventoryPersistencePort inventory,
                                      WarehouseReservationPersistencePort reservations,
                                      WarehouseDashboardQueryPort dashboard,
                                      ConfigureWarehouse configureWarehouse,
                                      ConfigureWarehouseZone configureWarehouseZone,
                                      ReceiveInventory receiveInventory,
                                      AdjustInventory adjustInventory,
                                      RegisterWaste registerWaste,
                                      BlockLot blockLot,
                                      QuarantineLot quarantineLot,
                                      RestoreLot restoreLot,
                                      PrepareFulfillment prepareFulfillment,
                                      ReserveInventory reserveInventory,
                                      ReleaseReservation releaseReservation,
                                      ExpireReservation expireReservation,
                                      MarkFulfillmentReady markFulfillmentReady,
                                      QueryAvailability queryAvailability,
                                      ManageSafetyStock manageSafetyStock,
                                      TransferInventory transferInventory,
                                      CycleCountInventory cycleCountInventory) {
        this.configuration = configuration;
        this.inventory = inventory;
        this.reservations = reservations;
        this.dashboard = dashboard;
        this.configureWarehouse = configureWarehouse;
        this.configureWarehouseZone = configureWarehouseZone;
        this.receiveInventory = receiveInventory;
        this.adjustInventory = adjustInventory;
        this.registerWaste = registerWaste;
        this.blockLot = blockLot;
        this.quarantineLot = quarantineLot;
        this.restoreLot = restoreLot;
        this.prepareFulfillment = prepareFulfillment;
        this.reserveInventory = reserveInventory;
        this.releaseReservation = releaseReservation;
        this.expireReservation = expireReservation;
        this.markFulfillmentReady = markFulfillmentReady;
        this.queryAvailability = queryAvailability;
        this.manageSafetyStock = manageSafetyStock;
        this.transferInventory = transferInventory;
        this.cycleCountInventory = cycleCountInventory;
    }

    public Page<WarehouseSummary> warehouses(CurrentAccessContext context, int page, int size, String sort) { return configuration.warehouses(context, page, size, sort); }
    public WarehouseSummary warehouse(CurrentAccessContext context, String id) { return configuration.warehouse(context, id); }
    public WarehouseSummary createWarehouse(CurrentAccessContext context, String code, String name, String address) { return configureWarehouse.create(context, code, name, address); }
    public WarehouseSummary updateWarehouse(CurrentAccessContext context, String id, String name, String address, String status, long expected) { return configureWarehouse.update(context, id, name, address, status, expected); }
    public OperationalProfile operationalProfile(CurrentAccessContext context, String id) { return configuration.operationalProfile(context, id); }
    @Transactional public OperationalProfile updateOperationalProfile(CurrentAccessContext context, String id, OperationalPatch patch, long expected) {
        return configuration.updateOperationalProfile(context, id, patch, expected);
    }
    public List<BuyerWarehouse> buyerWarehouses(CurrentAccessContext context) { return configuration.buyerWarehouses(context); }
    public Page<ZoneSummary> zones(CurrentAccessContext context, String warehouseId, int page, int size) { return configuration.zones(context, warehouseId, page, size); }
    public ZoneSummary createZone(CurrentAccessContext context, String warehouseId, String code, String name, String type, BigDecimal min, BigDecimal max) { return configureWarehouseZone.create(context, warehouseId, code, name, type, min, max); }
    public ZoneSummary updateZone(CurrentAccessContext context, String warehouseId, String zoneId, String name, BigDecimal min, BigDecimal max, String status, long expected) { return configureWarehouseZone.update(context, warehouseId, zoneId, name, min, max, status, expected); }
    public Page<LotSummary> lots(CurrentAccessContext context, String catalogItemId, String warehouseId, String zoneId, String status, int page, int size, String sort) { return inventory.lots(context, catalogItemId, warehouseId, zoneId, status, page, size, sort); }
    public Page<MovementSummary> movements(CurrentAccessContext context, String lotId, int page, int size, String sort) { return inventory.movements(context, lotId, page, size, sort); }
    public LotSummary lot(CurrentAccessContext context, String id) { return inventory.lot(context, id); }
    public LotSummary receive(CurrentAccessContext context, Receipt receipt, String key, String correlation) { return receiveInventory.execute(context, receipt, key, correlation); }
    public LotSummary adjust(CurrentAccessContext context, String lotId, BigDecimal quantity, boolean inbound, String reason, long expected, String key, String correlation) { return adjustInventory.execute(context, lotId, quantity, inbound, reason, expected, key, correlation); }
    public LotSummary waste(CurrentAccessContext context, String lotId, BigDecimal quantity, String reason, long expected, String key, String correlation) { return registerWaste.execute(context, lotId, quantity, reason, expected, key, correlation); }
    public LotSummary blockLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) { return blockLot.execute(context, lotId, expected, reason, key, correlation); }
    public LotSummary quarantineLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) { return quarantineLot.execute(context, lotId, expected, reason, key, correlation); }
    public LotSummary restoreLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) { return restoreLot.execute(context, lotId, expected, reason, key, correlation); }
    public LotSummary disposeLot(CurrentAccessContext context, String lotId, String disposition, long expected, String reason, String key, String correlation) { return inventory.disposeLot(context, lotId, disposition, expected, reason, key, correlation); }
    @Transactional public LotSummary disposeTemperatureQuantity(CurrentAccessContext context, String lotId,
            String disposition, BigDecimal affectedQuantity, java.util.UUID temperatureEvaluationId,
            long expected, String reason, String key, String correlation) {
        return inventory.disposeTemperatureQuantity(context, lotId, disposition, affectedQuantity,
                temperatureEvaluationId, expected, reason, key, correlation);
    }
    public CycleCountRecord recordCycleCount(CurrentAccessContext context, String lotId, CycleCountCommand command,
                                             long expectedLotVersion, String key, String correlation) {
        return cycleCountInventory.record(context, lotId, command, expectedLotVersion, key, correlation);
    }
    public CycleCountCorrection applyCycleCountCorrection(CurrentAccessContext context, String countId,
                                                          long expectedLotVersion, String key, String correlation) {
        return cycleCountInventory.applyCorrection(context, countId, expectedLotVersion, key, correlation);
    }
    public List<Availability> availability(CurrentAccessContext context, List<String> ids) { return queryAvailability.execute(context, ids); }
    public List<Availability> warehouseAvailability(CurrentAccessContext context, String warehouseId, List<String> ids) { return queryAvailability.executeWarehouse(context, warehouseId, ids); }
    public Page<SafetyStockSummary> safetyStocks(CurrentAccessContext context, String warehouseId, String skuId, int page, int size) {
        return manageSafetyStock.list(context, warehouseId, skuId, page, size);
    }
    public SafetyStockSummary safetyStock(CurrentAccessContext context, String id) { return manageSafetyStock.get(context, id); }
    public SafetyStockSummary upsertSafetyStock(CurrentAccessContext context, SafetyStockCommand command, long expectedVersion,
                                                String key, String correlation) {
        return manageSafetyStock.upsert(context, command, expectedVersion, key, correlation);
    }
    public Page<TransferSummary> transfers(CurrentAccessContext context, String sourceWarehouseId, String destinationWarehouseId,
                                           int page, int size) {
        return transferInventory.list(context, sourceWarehouseId, destinationWarehouseId, page, size);
    }
    public TransferSummary transfer(CurrentAccessContext context, String id) { return transferInventory.get(context, id); }
    public TransferSummary transfer(CurrentAccessContext context, TransferCommand command, long expectedSourceVersion,
                                    String key, String correlation) {
        return transferInventory.execute(context, command, expectedSourceVersion, key, correlation);
    }
    public TransferSummary dispatchTransfer(CurrentAccessContext context, String id, long expectedVersion,
                                            String key, String correlation) {
        return transferInventory.dispatch(context, id, expectedVersion, key, correlation);
    }
    public TransferSummary receiveTransfer(CurrentAccessContext context, String id, long expectedVersion,
                                           String key, String correlation) {
        return transferInventory.receive(context, id, expectedVersion, key, correlation);
    }
    @Transactional public TransferReceiptObservation observeTransferReceiptDiscrepancy(
            CurrentAccessContext context, String id, TransferReceiptObservationCommand command,
            long expectedVersion, String key, String correlation) {
        return transferInventory.observeReceiptDiscrepancy(context, id, command, expectedVersion, key, correlation);
    }
    public ReservationPreview preview(CurrentAccessContext context, String orderId) { return prepareFulfillment.execute(context, orderId); }
    public ReservationDetail reserve(CurrentAccessContext context, String orderId, long expected, String key, String correlation) { return reserveInventory.execute(context, orderId, expected, key, correlation); }
    public ReservationDetail release(CurrentAccessContext context, String reservationId, long expected, String key, String reason, String correlation, boolean expiry) {
        return expiry ? releaseReservation.expire(context, reservationId, expected, key, reason, correlation)
                : releaseReservation.execute(context, reservationId, expected, key, reason, correlation);
    }
    public Page<ReservationSummary> reservations(CurrentAccessContext context, String status, int page, int size) { return reservations.reservations(context, status, page, size); }
    public ReservationDetail reservation(CurrentAccessContext context, String id) { return reservations.reservation(context, id); }
    public List<ReadinessCandidate> readiness(CurrentAccessContext context) { return markFulfillmentReady.execute(context); }
    public void expireReservations() { expireReservation.execute(); }

}
