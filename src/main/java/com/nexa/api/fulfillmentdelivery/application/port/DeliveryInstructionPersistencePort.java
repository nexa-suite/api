package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgeRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgementResult;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.DispatchInstructionScope;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.InstructionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishedInstruction;

import java.util.Optional;
import java.util.UUID;

/** Scope-bound queries and commands for operational delivery instructions. */
public interface DeliveryInstructionPersistencePort {
    InstructionSetView findForDriver(UUID tenantId, UUID workspaceId, UUID membershipId, UUID deliveryId);

    InstructionSetView findForDispatch(UUID tenantId, UUID workspaceId, UUID deliveryId);

    Optional<DispatchInstructionScope> findDispatchScope(UUID tenantId, UUID workspaceId, UUID deliveryId);

    PublishedInstruction publish(PublishRequest request);

    AcknowledgementResult acknowledge(AcknowledgeRequest request);
}
