package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;

import java.util.List;

/** Cohesive read boundary for Warehouse dashboard and fulfillment readiness projections. */
public interface WarehouseDashboardQueryPort {
    List<WarehouseOperationsService.ReadinessCandidate> readiness(CurrentAccessContext context);
}
