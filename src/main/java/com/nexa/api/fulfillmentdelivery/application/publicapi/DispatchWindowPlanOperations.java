package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.Request;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.View;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Explicit dispatch-window planning use case. */
public interface DispatchWindowPlanOperations {
    View plan(CurrentAccessContext context, UUID fulfillmentId, long expectedFulfillmentVersion,
               Request request, String idempotencyKey);
}
