package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentWorkListModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

public interface FulfillmentWorkListQueries {
    FulfillmentWorkListModels.Page list(CurrentAccessContext context, int page, int size);
}
