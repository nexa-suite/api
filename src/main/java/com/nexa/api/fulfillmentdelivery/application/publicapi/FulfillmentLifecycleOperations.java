package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels.TemperatureEvidenceView;
import com.nexa.api.fulfillmentdelivery.application.model.PhysicalAllocationModels;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentLifecycleModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.UUID;

/** Scoped fulfillment and delivery operations used by the BC-06 request boundary. */
public interface FulfillmentLifecycleOperations {
    FulfillmentModels.FulfillmentView start(CurrentAccessContext context, UUID salesOrderId,
                                            long expectedOrderVersion, String idempotencyKey);
    FulfillmentModels.FulfillmentView get(CurrentAccessContext context, UUID fulfillmentId);
    PhysicalAllocationModels.PhysicalAllocationView getPhysicalAllocation(CurrentAccessContext context,
                                                                           UUID fulfillmentId);
    FulfillmentModels.FulfillmentView startPicking(CurrentAccessContext context, UUID fulfillmentId,
                                                    long expectedVersion, String idempotencyKey);
    FulfillmentModels.FulfillmentView confirmPicking(CurrentAccessContext context, UUID fulfillmentId,
                                                      long expectedVersion, String idempotencyKey,
                                                      FulfillmentLifecycleModels.PickingCommand command);
    FulfillmentModels.FulfillmentView resolveShortage(CurrentAccessContext context, UUID fulfillmentId,
                                                       long expectedVersion, String idempotencyKey,
                                                       FulfillmentLifecycleModels.ShortageResolutionCommand command);
    FulfillmentModels.FulfillmentView pack(CurrentAccessContext context, UUID fulfillmentId,
                                           long expectedVersion, String idempotencyKey);
    FulfillmentModels.FulfillmentView stage(CurrentAccessContext context, UUID fulfillmentId,
                                            long expectedVersion, String idempotencyKey);
    FulfillmentModels.FulfillmentView readyForDispatch(CurrentAccessContext context, UUID fulfillmentId,
                                                       long expectedVersion, String idempotencyKey);
    FulfillmentModels.FulfillmentView dispatch(CurrentAccessContext context, UUID fulfillmentId,
                                               long expectedVersion, String idempotencyKey,
                                               FulfillmentModels.DispatchRequest frozenFacts);
    FulfillmentModels.HandoffEvidence handoffEvidence(CurrentAccessContext context, UUID fulfillmentId);
    FulfillmentModels.DeliveryView getDelivery(CurrentAccessContext context, UUID deliveryId);
    FulfillmentModels.PodView currentPod(CurrentAccessContext context, UUID deliveryId);
    FulfillmentModels.DeliveryView startDelivery(CurrentAccessContext context, UUID deliveryId,
                                                 long expectedVersion, String idempotencyKey);
    FulfillmentModels.DeliveryOutcomeResult recordAttempt(CurrentAccessContext context, UUID deliveryId,
                                                           long expectedVersion, String idempotencyKey,
                                                           FulfillmentLifecycleModels.AttemptCommand command);
    FulfillmentModels.PodView capturePod(CurrentAccessContext context, UUID deliveryId, long expectedVersion,
                                         String idempotencyKey, FulfillmentLifecycleModels.PodCommand command);
    FulfillmentModels.PodView sealPod(CurrentAccessContext context, UUID deliveryId, long expectedVersion,
                                      String idempotencyKey, Instant sealedAt);
    FulfillmentModels.TemperatureView recordTemperature(CurrentAccessContext context, UUID deliveryId,
                                                        long expectedVersion, String idempotencyKey,
                                                        FulfillmentLifecycleModels.TemperatureCommand command);
    FulfillmentModels.FulfillmentTemperatureReadiness fulfillmentTemperatureReadiness(
            CurrentAccessContext context, UUID fulfillmentId);
    TemperatureEvidenceView recordFulfillmentTemperatureEvidence(
            CurrentAccessContext context, UUID fulfillmentId, long expectedFulfillmentVersion,
            String idempotencyKey, FulfillmentLifecycleModels.FulfillmentTemperatureEvidenceCommand command);
    TemperatureEvidenceView recordTemperatureEvidence(CurrentAccessContext context, String idempotencyKey,
                                                       FulfillmentLifecycleModels.TemperatureEvidenceCommand command);
    TemperatureEvidenceView temperatureEvidence(CurrentAccessContext context, UUID evidenceId);
}
