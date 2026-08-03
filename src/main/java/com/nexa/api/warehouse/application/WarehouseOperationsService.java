package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseOperationsPort;
import com.nexa.api.warehouse.application.port.WarehouseDashboardQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Warehouse application boundary. Persistence and SQL stay behind WarehouseOperationsPort. */
@Service
@Profile("!test")
public class WarehouseOperationsService {
    private final WarehouseOperationsPort operations;
    private final WarehouseDashboardQueryPort dashboard;

    public WarehouseOperationsService(WarehouseOperationsPort operations, WarehouseDashboardQueryPort dashboard) {
        this.operations = operations;
        this.dashboard = dashboard;
    }

    public Page<WarehouseSummary> warehouses(CurrentAccessContext context, int page, int size, String sort) { return operations.warehouses(context, page, size, sort); }
    public WarehouseSummary warehouse(CurrentAccessContext context, String id) { return operations.warehouse(context, id); }
    public WarehouseSummary createWarehouse(CurrentAccessContext context, String code, String name, String address) { return operations.createWarehouse(context, code, name, address); }
    public WarehouseSummary updateWarehouse(CurrentAccessContext context, String id, String name, String address, String status, long expected) { return operations.updateWarehouse(context, id, name, address, status, expected); }
    public OperationalProfile operationalProfile(CurrentAccessContext context, String id) { return operations.operationalProfile(context, id); }
    public OperationalProfile updateOperationalProfile(CurrentAccessContext context, String id, OperationalPatch patch, long expected) {
        return operations.updateOperationalProfile(context, id, patch, expected);
    }
    public List<BuyerWarehouse> buyerWarehouses(CurrentAccessContext context) { return operations.buyerWarehouses(context); }
    public Page<ZoneSummary> zones(CurrentAccessContext context, String warehouseId, int page, int size) { return operations.zones(context, warehouseId, page, size); }
    public ZoneSummary createZone(CurrentAccessContext context, String warehouseId, String code, String name, String type, BigDecimal min, BigDecimal max) { return operations.createZone(context, warehouseId, code, name, type, min, max); }
    public ZoneSummary updateZone(CurrentAccessContext context, String warehouseId, String zoneId, String name, BigDecimal min, BigDecimal max, String status, long expected) { return operations.updateZone(context, warehouseId, zoneId, name, min, max, status, expected); }
    public Page<LotSummary> lots(CurrentAccessContext context, String catalogItemId, String warehouseId, String zoneId, String status, int page, int size, String sort) { return operations.lots(context, catalogItemId, warehouseId, zoneId, status, page, size, sort); }
    public Page<MovementSummary> movements(CurrentAccessContext context, String lotId, int page, int size, String sort) { return operations.movements(context, lotId, page, size, sort); }
    public LotSummary lot(CurrentAccessContext context, String id) { return operations.lot(context, id); }
    public LotSummary receive(CurrentAccessContext context, Receipt receipt, String key, String correlation) { return operations.receive(context, receipt, key, correlation); }
    public LotSummary adjust(CurrentAccessContext context, String lotId, BigDecimal quantity, boolean inbound, String reason, long expected, String key, String correlation) { return operations.adjust(context, lotId, quantity, inbound, reason, expected, key, correlation); }
    public LotSummary waste(CurrentAccessContext context, String lotId, BigDecimal quantity, String reason, long expected, String key, String correlation) { return operations.waste(context, lotId, quantity, reason, expected, key, correlation); }
    public LotSummary blockLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) { return operations.blockLot(context, lotId, expected, reason, key, correlation); }
    public LotSummary quarantineLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) { return operations.quarantineLot(context, lotId, expected, reason, key, correlation); }
    public LotSummary restoreLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation) { return operations.restoreLot(context, lotId, expected, reason, key, correlation); }
    public List<Availability> availability(CurrentAccessContext context, List<String> ids) { return operations.availability(context, ids); }
    public ReservationPreview preview(CurrentAccessContext context, String orderId) { return operations.preview(context, orderId); }
    public ReservationDetail reserve(CurrentAccessContext context, String orderId, long expected, String key, String correlation) { return operations.reserve(context, orderId, expected, key, correlation); }
    public ReservationDetail release(CurrentAccessContext context, String reservationId, long expected, String key, String reason, String correlation, boolean expiry) { return operations.release(context, reservationId, expected, key, reason, correlation, expiry); }
    public Page<ReservationSummary> reservations(CurrentAccessContext context, String status, int page, int size) { return operations.reservations(context, status, page, size); }
    public ReservationDetail reservation(CurrentAccessContext context, String id) { return operations.reservation(context, id); }
    public List<ReadinessCandidate> readiness(CurrentAccessContext context) { return dashboard.readiness(context); }
    public void expireReservations() { operations.expireReservations(); }

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
    public record LotSummary(String id, String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, Instant receivedAt, BigDecimal onHand, BigDecimal reserved, BigDecimal available, String unit, String status, long version) { }
    public record MovementSummary(String id, String lotId, String catalogItemId, String type, BigDecimal quantity, String unit, BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal reservedBefore, BigDecimal reservedAfter, String reason, Instant occurredAt) { }
    public record Receipt(String warehouseId, String zoneId, String catalogItemId, String batchNumber, LocalDate expirationDate, BigDecimal quantity, String unit, BigDecimal temperatureReading, String notes) { }
    public record Availability(String catalogItemId, String status, Instant asOf) { }
    public record ReservationPreview(String salesOrderId, String orderNumber, List<ProposalLine> lines, boolean complete, Instant generatedAt, String notice) { }
    public record ProposalLine(String catalogItemId, BigDecimal requested, String unit, List<AllocationView> allocations, BigDecimal shortage, boolean complete) { }
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
