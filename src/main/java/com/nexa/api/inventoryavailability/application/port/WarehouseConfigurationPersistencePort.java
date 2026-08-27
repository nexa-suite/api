package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;

import java.math.BigDecimal;
import java.util.List;

public interface WarehouseConfigurationPersistencePort {
    WarehouseOperationsService.Page<WarehouseOperationsService.WarehouseSummary> warehouses(CurrentAccessContext context, int page, int size, String sort);
    WarehouseOperationsService.WarehouseSummary warehouse(CurrentAccessContext context, String id);
    WarehouseOperationsService.WarehouseSummary createWarehouse(CurrentAccessContext context, String code, String name, String address);
    WarehouseOperationsService.WarehouseSummary updateWarehouse(CurrentAccessContext context, String id, String name, String address, String status, long expected);
    WarehouseOperationsService.OperationalProfile operationalProfile(CurrentAccessContext context, String id);
    WarehouseOperationsService.OperationalProfile updateOperationalProfile(CurrentAccessContext context, String id, WarehouseOperationsService.OperationalPatch patch, long expected);
    List<WarehouseOperationsService.BuyerWarehouse> buyerWarehouses(CurrentAccessContext context);
    WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(CurrentAccessContext context, String warehouseId, int page, int size);
    WarehouseOperationsService.ZoneSummary createZone(CurrentAccessContext context, String warehouseId, String code, String name, String type, BigDecimal min, BigDecimal max);
    WarehouseOperationsService.ZoneSummary updateZone(CurrentAccessContext context, String warehouseId, String zoneId, String name, BigDecimal min, BigDecimal max, String status, long expected);
}
