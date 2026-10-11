package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgementResult;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.InstructionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishedInstruction;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

/** Dispatch instruction publication and assigned-driver acknowledgement use cases. */
public interface DeliveryInstructionOperations {
    InstructionSetView getForDriver(CurrentAccessContext context, UUID deliveryId);

    InstructionSetView getForDispatch(CurrentAccessContext context, UUID deliveryId);

    AcknowledgementResult acknowledge(CurrentAccessContext context, UUID deliveryId,
                                      long expectedInstructionSetVersion, List<UUID> instructionIds,
                                      String idempotencyKey);

    PublishedInstruction publish(CurrentAccessContext context, UUID deliveryId,
                                 long expectedDeliveryVersion, UUID instructionId,
                                 DeliveryInstructionKind kind, String content, String idempotencyKey);
}
