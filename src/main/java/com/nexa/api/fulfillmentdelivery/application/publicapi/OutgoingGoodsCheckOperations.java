package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.OutgoingGoodsCheckModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

public interface OutgoingGoodsCheckOperations {
    OutgoingGoodsCheckModels.Check record(CurrentAccessContext context, UUID fulfillmentId,
                                          long expectedFulfillmentVersion, String idempotencyKey,
                                          OutgoingGoodsCheckModels.Request request);
    OutgoingGoodsCheckModels.Check current(CurrentAccessContext context, UUID fulfillmentId);
    OutgoingGoodsCheckModels.DispatchSummary currentDispatchSummary(CurrentAccessContext context,
                                                                     UUID fulfillmentId);
    OutgoingGoodsCheckModels.DiscrepancyResolution resolveDiscrepancy(
            CurrentAccessContext context, UUID fulfillmentId, long expectedFulfillmentVersion,
            String idempotencyKey, OutgoingGoodsCheckModels.ResolutionRequest request);
}
