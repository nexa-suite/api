package com.nexa.api.inventoryavailability.application.publicapi;

import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases.CaseFact;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** BC-05 application operations for recording and submitting receiving discrepancies. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface InboundReceivingDiscrepancyUseCase {
    CaseFact create(CurrentAccessContext context, UUID warehouseId, UUID expectedSkuId, UUID observedSkuId,
                    String expectedBatchReference, String observedBatchReference, BigDecimal expectedQuantity,
                    BigDecimal observedQuantity, String unit, String reason, String observationNotes,
                    String idempotencyKey);

    CaseFact submit(CurrentAccessContext context, UUID caseId, UUID evidenceObjectId, long expectedVersion,
                    String idempotencyKey);
}
