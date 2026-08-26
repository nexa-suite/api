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
public class WarehouseOperationsService {
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

    public WarehouseOperationsService(WarehouseConfigurationPersistencePort configuration,
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
                                      TransferInventory transferInventory) {
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
    public List<Availability> availability(CurrentAccessContext context, List<String> ids) { return queryAvailability.execute(context, ids); }
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

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
    }
    public record WarehouseSummary(String id, String code, String name, String address, String status, long version) { }
    public record OperationalProfile(String id, String code, String name, String address, String status,
                                     LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                     boolean serviceable, String selectionPolicy, long version,
                                     long settingsVersion, BigDecimal latitude, BigDecimal longitude) { }
    public record BuyerWarehouse(String id, String code, String name, String address,
                                 LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                 boolean serviceable, long version,
                                 BigDecimal latitude, BigDecimal longitude) { }
    public record OperationalPatch(String name, String address, String status, String selectionPolicy,
                                   LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                   Boolean serviceable, BigDecimal latitude, BigDecimal longitude) {
        public OperationalPatch(String name, String address, String status, String selectionPolicy,
                                LocalTime operatingHoursStart, LocalTime operatingHoursEnd,
                                Boolean serviceable) {
            this(name, address, status, selectionPolicy, operatingHoursStart, operatingHoursEnd, serviceable, null, null);
        }
    }
    public record ZoneSummary(String id, String warehouseId, String code, String name, String type, BigDecimal temperatureMin, BigDecimal temperatureMax, String status, long version) { }
    public record LotSummary(String id, String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, Instant receivedAt, BigDecimal onHand, BigDecimal reserved, BigDecimal available, String unit, String status, long version, String skuId) {
        public LotSummary(String id, String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, Instant receivedAt, BigDecimal onHand, BigDecimal reserved, BigDecimal available, String unit, String status, long version) {
            this(id, warehouseId, zoneId, catalogItemId, batchNumber, expirationDate, receivedAt, onHand, reserved, available, unit, status, version, null);
        }
    }
    public record SafetyStockCommand(String warehouseId, String skuId, String catalogItemId,
                                     BigDecimal quantity, String unit) { }
    public record SafetyStockSummary(String id, String warehouseId, String skuId, String catalogItemId,
                                     BigDecimal quantity, String unit, long version, Instant updatedAt) { }
    public record TransferCommand(String sourceLotId, String sourceWarehouseId, String sourceZoneId,
                                  String destinationWarehouseId, String destinationZoneId,
                                  String skuId, String catalogItemId, BigDecimal quantity, String unit,
                                  String reason) { }
    public record TransferSummary(String id, String sourceWarehouseId, String sourceZoneId, String sourceLotId,
                                  String destinationWarehouseId, String destinationZoneId, String destinationLotId,
                                  String skuId, String catalogItemId, String batchNumber, LocalDate expirationDate,
                                  BigDecimal requestedQuantity, BigDecimal transferredQuantity, String unit,
                                  String mode, String status, String reason, Instant createdAt,
                                  long sourceVersionBefore, long sourceVersionAfter, long destinationVersionAfter) { }
    public record MovementSummary(String id, String lotId, String catalogItemId, String type, BigDecimal quantity, String unit, BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore, BigDecimal reservedAfter, String reason, Instant occurredAt, String skuId) {
        public MovementSummary(String id, String lotId, String catalogItemId, String type, BigDecimal quantity, String unit, BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore, BigDecimal reservedAfter, String reason, Instant occurredAt) {
            this(id, lotId, catalogItemId, type, quantity, unit, quantityBefore, quantityAfter, reservedBefore, reservedAfter, reason, occurredAt, null);
        }
    }
    public record Receipt(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes, String skuId) {
        public Receipt(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes) {
            this(warehouseId, zoneId, catalogItemId, batchNumber, expirationDate, quantity, unit, temperatureReading, notes, null);
        }
    }
    public record Availability(String catalogItemId, String status, Instant asOf,
                               BigDecimal physicalQuantity, BigDecimal safetyStock, BigDecimal sellableQuantity) {
        public Availability(String catalogItemId, String status, Instant asOf) {
            this(catalogItemId, status, asOf, null, null, null);
        }
    }
    public record ReservationPreview(String salesOrderId, String orderNumber, List<ProposalLine> lines, boolean complete, Instant generatedAt, String notice) { }
    public record ProposalLine(String catalogItemId, BigDecimal requested, String unit, List<AllocationView> allocations, BigDecimal shortage, boolean complete, String skuId) {
        public ProposalLine(String catalogItemId, BigDecimal requested, String unit, List<AllocationView> allocations, BigDecimal shortage, boolean complete) {
            this(catalogItemId, requested, unit, allocations, shortage, complete, null);
        }
    }
    public record AllocationView(String lotId, BigDecimal quantity, String unit, LocalDate expirationDate) { }
    public record ReservationSummary(String id, String salesOrderId, String orderNumber, String status, Instant createdAt, Instant reservedAt, Instant expiresAt, long version) { }
    public record ReservationDetail(String id, String salesOrderId, String orderNumber, String status, Instant createdAt, Instant reservedAt, Instant expiresAt, long version, String clientAccountId, List<AllocationView> allocations) { }
    public record ReadinessCandidate(String reservationId, String salesOrderId, String orderNumber, String clientAccountId, int lineCount, BigDecimal totalReservedQuantity, Instant reservedAt, Instant expiresAt, String readinessStatus) { }

    public static final class WarehouseException extends RuntimeException {
        private final String code;
        private final boolean notFound;
        public WarehouseException(String code, boolean notFound) { super(code); this.code = code; this.notFound = notFound; }
        public String code() { return code; }
        public boolean notFound() { return notFound; }
    }
}
