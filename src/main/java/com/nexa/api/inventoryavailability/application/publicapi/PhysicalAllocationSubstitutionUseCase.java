package com.nexa.api.inventoryavailability.application.publicapi;

import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionRequests.Result;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** BC-05 application operation for creating a controlled lot-substitution request. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface PhysicalAllocationSubstitutionUseCase {
    Result request(CurrentAccessContext context, UUID fulfillmentId, UUID allocationId,
                   UUID physicalAllocationLineId, UUID expectedLotId, UUID alternativeLotId,
                   BigDecimal quantity, String unit, String reason, String idempotencyKey,
                   long expectedAllocationVersion);
}
