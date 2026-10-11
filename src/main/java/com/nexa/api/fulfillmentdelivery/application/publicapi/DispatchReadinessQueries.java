package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

public interface DispatchReadinessQueries {
    DispatchReadinessModels.Page list(CurrentAccessContext context, int page, int size);
    DispatchReadinessModels.Readiness readiness(CurrentAccessContext context, UUID fulfillmentId);
}
