package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.function.Function;

/** Runs a fulfillment query using the caller's verified scope. */
@org.springframework.modulith.NamedInterface(value = "fulfillment-tenant-composition", propagate = false)
public interface FulfillmentDeliveryReadRunner {
    <T> T read(CurrentAccessContext context, FulfillmentDeliveryRequestRunner.Requirements requirements,
               Function<FulfillmentDeliveryComposition, T> query);

    /** Dispatch projections may lock the current Sales account assignment while selecting visible orders. */
    default <T> T readWithAccountLocks(CurrentAccessContext context,
                                       FulfillmentDeliveryRequestRunner.Requirements requirements,
                                       Function<FulfillmentDeliveryComposition, T> query) {
        return read(context, requirements, query);
    }
}
