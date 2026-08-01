package com.nexa.api.warehouse.application.port;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

import java.math.BigDecimal;
import java.util.List;

/** Persistence/query boundary for Warehouse use cases. JDBC stays behind this port. */
public interface WarehouseOperationsPort {
    WarehouseOperationsService.Page<WarehouseOperationsService.WarehouseSummary> warehouses(CurrentAccessContext context, int page, int size, String sort);
    WarehouseOperationsService.WarehouseSummary warehouse(CurrentAccessContext context, String id);
    WarehouseOperationsService.WarehouseSummary createWarehouse(CurrentAccessContext context, String code, String name, String address);
    WarehouseOperationsService.WarehouseSummary updateWarehouse(CurrentAccessContext context, String id, String name, String address, String status, long expected);
    WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(CurrentAccessContext context, String warehouseId, int page, int size);
    WarehouseOperationsService.ZoneSummary createZone(CurrentAccessContext context, String warehouseId, String code, String name, String type, BigDecimal min, BigDecimal max);
    WarehouseOperationsService.ZoneSummary updateZone(CurrentAccessContext context, String warehouseId, String zoneId, String name, BigDecimal min, BigDecimal max, String status, long expected);
    WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> lots(CurrentAccessContext context, String catalogItemId, String warehouseId, String zoneId, String status, int page, int size, String sort);
    WarehouseOperationsService.Page<WarehouseOperationsService.MovementSummary> movements(CurrentAccessContext context, String lotId, int page, int size, String sort);
    WarehouseOperationsService.LotSummary lot(CurrentAccessContext context, String id);
    WarehouseOperationsService.LotSummary receive(CurrentAccessContext context, WarehouseOperationsService.Receipt receipt, String key, String correlation);
    WarehouseOperationsService.LotSummary adjust(CurrentAccessContext context, String lotId, BigDecimal quantity, boolean inbound, String reason, long expected, String key, String correlation);
    WarehouseOperationsService.LotSummary waste(CurrentAccessContext context, String lotId, BigDecimal quantity, String reason, long expected, String key, String correlation);
    WarehouseOperationsService.LotSummary blockLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation);
    WarehouseOperationsService.LotSummary quarantineLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation);
    WarehouseOperationsService.LotSummary restoreLot(CurrentAccessContext context, String lotId, long expected, String reason, String key, String correlation);
    List<WarehouseOperationsService.Availability> availability(CurrentAccessContext context, List<String> ids);
    WarehouseOperationsService.ReservationPreview preview(CurrentAccessContext context, String orderId);
    WarehouseOperationsService.ReservationDetail reserve(CurrentAccessContext context, String orderId, long expected, String key, String correlation);
    WarehouseOperationsService.ReservationDetail release(CurrentAccessContext context, String reservationId, long expected, String key, String reason, String correlation, boolean expiry);
    WarehouseOperationsService.Page<WarehouseOperationsService.ReservationSummary> reservations(CurrentAccessContext context, String status, int page, int size);
    WarehouseOperationsService.ReservationDetail reservation(CurrentAccessContext context, String id);
    void expireReservations();
}
