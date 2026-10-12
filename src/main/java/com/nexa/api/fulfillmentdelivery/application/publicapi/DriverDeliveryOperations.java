package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.ProofEvidenceKind;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.ProofOfDeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentLifecycleModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

public interface DriverDeliveryOperations {
    List<DriverDeliveryModels.DeliveryView> listAssigned(CurrentAccessContext context);
    DriverDeliveryModels.DeliveryView getAssigned(CurrentAccessContext context, UUID deliveryId);
    DriverDeliveryModels.AttemptStartResult startAttempt(CurrentAccessContext context, UUID deliveryId,
                                                         long expectedVersion, String idempotencyKey);
    FulfillmentModels.DeliveryOutcomeResult recordOutcome(
            CurrentAccessContext context, UUID deliveryId, UUID attemptId, long expectedVersion,
            String idempotencyKey, FulfillmentLifecycleModels.AttemptCommand command);
    DriverDeliveryModels.ArrivalView signalArrival(CurrentAccessContext context, UUID deliveryId,
                                                   UUID attemptId, long expectedVersion, String idempotencyKey);
    ProofOfDeliveryView createProofOfDelivery(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                                              long expectedVersion, String idempotencyKey,
                                              FulfillmentLifecycleModels.DriverProofCommand command);
    ProofOfDeliveryView attachProofEvidence(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                                             UUID podId, long expectedVersion, String idempotencyKey,
                                             ProofEvidenceKind kind, UUID evidenceObjectId);
}
