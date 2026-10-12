package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Published Warehouse application use cases and request/response values. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface WarehouseOperationsService {
    Page<WarehouseSummary> warehouses(CurrentAccessContext context, int page, int size, String sort);
    WarehouseSummary warehouse(CurrentAccessContext context, String id);
    WarehouseSummary createWarehouse(CurrentAccessContext context, String code, String name, String address);
    WarehouseSummary updateWarehouse(CurrentAccessContext context, String id, String name, String address,
                                     String status, long expected);
    OperationalProfile operationalProfile(CurrentAccessContext context, String id);
    OperationalProfile updateOperationalProfile(CurrentAccessContext context, String id,
                                                  OperationalPatch patch, long expected);
    List<BuyerWarehouse> buyerWarehouses(CurrentAccessContext context);
    Page<ZoneSummary> zones(CurrentAccessContext context, String warehouseId, int page, int size);
    ZoneSummary createZone(CurrentAccessContext context, String warehouseId, String code, String name,
                           String type, BigDecimal min, BigDecimal max);
    ZoneSummary updateZone(CurrentAccessContext context, String warehouseId, String zoneId, String name,
                           BigDecimal min, BigDecimal max, String status, long expected);
    Page<LotSummary> lots(CurrentAccessContext context, String catalogItemId, String warehouseId,
                          String zoneId, String status, int page, int size, String sort);
    Page<MovementSummary> movements(CurrentAccessContext context, String lotId, int page, int size, String sort);
    LotSummary lot(CurrentAccessContext context, String id);
    LotSummary receive(CurrentAccessContext context, Receipt receipt, String key, String correlation);
    LotSummary adjust(CurrentAccessContext context, String lotId, BigDecimal quantity, boolean inbound,
                      String reason, long expected, String key, String correlation);
    LotSummary waste(CurrentAccessContext context, String lotId, BigDecimal quantity, String reason,
                     long expected, String key, String correlation);
    LotSummary blockLot(CurrentAccessContext context, String lotId, long expected, String reason,
                        String key, String correlation);
    LotSummary quarantineLot(CurrentAccessContext context, String lotId, long expected, String reason,
                            String key, String correlation);
    LotSummary restoreLot(CurrentAccessContext context, String lotId, long expected, String reason,
                          String key, String correlation);
    LotSummary disposeLot(CurrentAccessContext context, String lotId, String disposition, long expected,
                          String reason, String key, String correlation);
    LotSummary disposeTemperatureQuantity(CurrentAccessContext context, String lotId, String disposition,
                          BigDecimal affectedQuantity, java.util.UUID temperatureEvaluationId, long expected,
                          String reason, String key, String correlation);
    CycleCountRecord recordCycleCount(CurrentAccessContext context, String lotId, CycleCountCommand command,
                                      long expectedLotVersion, String key, String correlation);
    CycleCountCorrection applyCycleCountCorrection(CurrentAccessContext context, String countId,
                                      long expectedLotVersion, String key, String correlation);
    List<Availability> availability(CurrentAccessContext context, List<String> ids);
    List<Availability> warehouseAvailability(CurrentAccessContext context, String warehouseId, List<String> ids);
    Page<SafetyStockSummary> safetyStocks(CurrentAccessContext context, String warehouseId, String skuId,
                                          int page, int size);
    SafetyStockSummary safetyStock(CurrentAccessContext context, String id);
    SafetyStockSummary upsertSafetyStock(CurrentAccessContext context, SafetyStockCommand command,
                                         long expectedVersion, String key, String correlation);
    Page<TransferSummary> transfers(CurrentAccessContext context, String sourceWarehouseId,
                                    String destinationWarehouseId, int page, int size);
    TransferSummary transfer(CurrentAccessContext context, String id);
    TransferSummary transfer(CurrentAccessContext context, TransferCommand command, long expectedSourceVersion,
                             String key, String correlation);
    TransferSummary dispatchTransfer(CurrentAccessContext context, String id, long expectedVersion,
                                     String key, String correlation);
    TransferSummary receiveTransfer(CurrentAccessContext context, String id, long expectedVersion,
                                    String key, String correlation);
    TransferReceiptObservation observeTransferReceiptDiscrepancy(CurrentAccessContext context, String id,
                                    TransferReceiptObservationCommand command, long expectedVersion,
                                    String key, String correlation);
    ReservationPreview preview(CurrentAccessContext context, String orderId);
    ReservationDetail reserve(CurrentAccessContext context, String orderId, long expected,
                              String key, String correlation);
    ReservationDetail release(CurrentAccessContext context, String reservationId, long expected, String key,
                              String reason, String correlation, boolean expiry);
    Page<ReservationSummary> reservations(CurrentAccessContext context, String status, int page, int size);
    ReservationDetail reservation(CurrentAccessContext context, String id);
    List<ReadinessCandidate> readiness(CurrentAccessContext context);
    void expireReservations();

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
                                  long sourceVersionBefore, Long sourceVersionAfter, Long destinationVersionAfter,
                                  long version, Instant dispatchedAt, Instant receivedAt) { }
    public record TransferReceiptObservationCommand(String observedBatchNumber,
                                                    LocalDate observedExpirationDate,
                                                    BigDecimal observedQuantity, String unit) { }
    public record TransferReceiptObservation(String id, String transferId, long transferVersion,
                                             String sourceWarehouseId, String sourceZoneId, String sourceLotId,
                                             String destinationWarehouseId, String destinationZoneId,
                                             String expectedBatchNumber, LocalDate expectedExpirationDate,
                                             BigDecimal expectedQuantity, String expectedUnit,
                                             String observedBatchNumber, LocalDate observedExpirationDate,
                                             BigDecimal observedQuantity, String observedUnit,
                                             String actorMembershipId, Instant recordedAt) {
        public boolean hasDifference() {
            return !expectedBatchNumber.equals(observedBatchNumber)
                    || (observedExpirationDate != null && !expectedExpirationDate.equals(observedExpirationDate))
                    || expectedQuantity.compareTo(observedQuantity) != 0;
        }
    }
    public record CycleCountCommand(BigDecimal observedQuantity, String unit) { }
    public record CycleCountRecord(String id, String lotId, String warehouseId, String zoneId,
                                   long lotVersion, BigDecimal expectedQuantity, BigDecimal observedQuantity,
                                   String unit, String status, String actorMembershipId, Instant recordedAt) { }
    public record CycleCountCorrection(String id, String cycleCountId, String lotId, String warehouseId,
                                       String zoneId, long lotVersionBefore, long lotVersionAfter,
                                       BigDecimal quantityBefore, BigDecimal quantityAfter,
                                       BigDecimal quantityDelta, String unit, String actorMembershipId,
                                       Instant recordedAt) { }
    public record MovementSummary(String id, String lotId, String catalogItemId, String type, BigDecimal quantity, String unit, BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore, BigDecimal reservedAfter, String reason, Instant occurredAt, String skuId) {
        public MovementSummary(String id, String lotId, String catalogItemId, String type, BigDecimal quantity, String unit, BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore, BigDecimal reservedAfter, String reason, Instant occurredAt) {
            this(id, lotId, catalogItemId, type, quantity, unit, quantityBefore, quantityAfter, reservedBefore, reservedAfter, reason, occurredAt, null);
        }
    }
    public record Receipt(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes, String skuId, String temperatureEvidenceObjectId) {
        public Receipt(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes, String skuId) {
            this(warehouseId, zoneId, catalogItemId, batchNumber, expirationDate, quantity, unit,
                    temperatureReading, notes, skuId, null);
        }
        public Receipt(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes) {
            this(warehouseId, zoneId, catalogItemId, batchNumber, expirationDate, quantity, unit,
                    temperatureReading, notes, null, null);
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

    public static final class WarehouseException extends com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationException {
        public WarehouseException(String code, boolean notFound) { super(code, notFound); }
    }
}
