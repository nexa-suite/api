package com.nexa.api.warehouse.application.port;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

import java.math.BigDecimal;
import java.util.List;

public interface WarehouseInventoryPersistencePort {
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
}
