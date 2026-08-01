package com.nexa.api.warehouse.application.port;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

import java.util.List;

/** Cohesive read boundary for Warehouse dashboard and fulfillment readiness projections. */
public interface WarehouseDashboardQueryPort {
    List<WarehouseOperationsService.ReadinessCandidate> readiness(CurrentAccessContext context);
}
