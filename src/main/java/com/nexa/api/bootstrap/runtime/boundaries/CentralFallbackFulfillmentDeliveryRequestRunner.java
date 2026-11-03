package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;
import java.util.function.Function;

/** Guards the retained central Fulfillment composition from bound and unknown Tenants. */
public final class CentralFallbackFulfillmentDeliveryRequestRunner implements FulfillmentDeliveryRequestRunner {
    private final FulfillmentDeliveryRequestRunner central;
    private final CentralTenantBusinessDataAccessGuard guard;

    public CentralFallbackFulfillmentDeliveryRequestRunner(FulfillmentDeliveryRequestRunner central,
            CentralTenantBusinessDataAccessGuard guard) {
        this.central = Objects.requireNonNull(central, "Central Fulfillment runner is required");
        this.guard = Objects.requireNonNull(guard, "Tenant business access guard is required");
    }

    @Override
    public <T> T execute(CurrentAccessContext context, Requirements requirements,
            Function<FulfillmentDeliveryComposition, T> work) {
        guard.requireTenantBoundRoute(context);
        return central.execute(context, requirements, work);
    }
}
