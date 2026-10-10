package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.function.Function;

/** Runs one Warehouse HTTP request against its authoritative persistence binding. */
public interface WarehouseOperationsRequestRunner {
    <T> T execute(CurrentAccessContext context, Function<WarehouseOperationsService, T> operation);
}
