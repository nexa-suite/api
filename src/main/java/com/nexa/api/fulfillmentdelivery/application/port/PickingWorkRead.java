package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentWorkListModels;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.model.PhysicalAllocationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import java.util.UUID;

@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface PickingWorkRead {
    FulfillmentWorkListModels.Page list(CurrentAccessContext context, int page, int size);
    FulfillmentModels.FulfillmentView fulfillment(CurrentAccessContext context, UUID id);
    PhysicalAllocationModels.PhysicalAllocationView allocation(CurrentAccessContext context, UUID id);
}
