package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.LoadView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

/** Dispatch load planning and assigned-driver load operations. */
public interface DeliveryLoadOperations {
    List<LoadView> dispatchLoads(CurrentAccessContext context);

    List<LoadView> driverLoads(CurrentAccessContext context);

    LoadView getDispatchLoad(CurrentAccessContext context, UUID loadId);

    LoadView create(CurrentAccessContext context, DeliveryLoadModels.CreateLoadRequest request,
                    String idempotencyKey);

    LoadView reorder(CurrentAccessContext context, UUID loadId, long expectedVersion,
                     DeliveryLoadModels.ReorderStopsRequest request, String idempotencyKey);

    LoadView assign(CurrentAccessContext context, UUID loadId, long expectedVersion,
                    DeliveryLoadModels.AssignDriverRequest request, String idempotencyKey);

    LoadView offer(CurrentAccessContext context, UUID loadId, long expectedVersion, String idempotencyKey);

    LoadView confirmHandoff(CurrentAccessContext context, UUID loadId, long expectedVersion,
                            String idempotencyKey);

    LoadView accept(CurrentAccessContext context, UUID loadId, long expectedVersion, String idempotencyKey);
}
