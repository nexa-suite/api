package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ExceptionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.MutationResult;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Assigned-driver operational-exception lifecycle use cases. */
public interface OperationalExceptionOperations {
    ExceptionSetView getForDriver(CurrentAccessContext context, UUID deliveryId);

    MutationResult claim(CurrentAccessContext context, UUID deliveryId, UUID exceptionId,
                         long expectedDeliveryVersion, String idempotencyKey);

    MutationResult review(CurrentAccessContext context, UUID deliveryId, UUID exceptionId,
                          long expectedDeliveryVersion, String idempotencyKey);

    MutationResult completeWarning(CurrentAccessContext context, UUID deliveryId, UUID exceptionId,
                                   long expectedDeliveryVersion, String idempotencyKey,
                                   String resolution, boolean close);
}
