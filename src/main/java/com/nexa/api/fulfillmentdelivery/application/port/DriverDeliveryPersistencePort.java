package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartResult;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.ArrivalRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.ArrivalView;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.DeliveryView;

import java.util.List;
import java.util.UUID;

/** Scope-bound BC-06 query and active-attempt command port for drivers. */
public interface DriverDeliveryPersistencePort {
    List<DeliveryView> listAssigned(UUID tenantId, UUID workspaceId, UUID membershipId);

    DeliveryView findAssigned(UUID tenantId, UUID workspaceId, UUID membershipId, UUID deliveryId);

    void requireAssignedAttempt(UUID tenantId, UUID workspaceId, UUID membershipId,
                                UUID deliveryId, UUID attemptId, String idempotencyKey);

    void requireAssignedTerminalAttempt(UUID tenantId, UUID workspaceId, UUID membershipId,
                                         UUID deliveryId, UUID attemptId);

    AttemptStartResult startAttempt(AttemptStartRequest request);

    ArrivalView signalArrival(ArrivalRequest request);
}
