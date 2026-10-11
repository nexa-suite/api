package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.CustomerInstructionModels.Snapshot;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Customer-reported and Buyer-authored delivery instruction use cases. */
public interface CustomerInstructionOperations {
    Snapshot read(CurrentAccessContext context, UUID salesOrderId, boolean buyer);

    Snapshot publish(CurrentAccessContext context, UUID salesOrderId, boolean buyer,
                     long expectedVersion, UUID instructionId, DeliveryInstructionKind kind,
                     String content, String sourceReference, String idempotencyKey);
}
