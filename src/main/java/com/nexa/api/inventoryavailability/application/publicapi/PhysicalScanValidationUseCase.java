package com.nexa.api.inventoryavailability.application.publicapi;

import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.PickingScanValidationRequest;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.PickingScanValidationResult;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** BC-05 read-only application operation for validating one physical picking scan. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface PhysicalScanValidationUseCase {
    PickingScanValidationResult validate(CurrentAccessContext context, PickingScanValidationRequest request);
}
