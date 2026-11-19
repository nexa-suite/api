package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.publicapi.BuyerDeliveryTrackingQueries;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.modulith.NamedInterface;

import java.util.function.Function;

/** Runs a Buyer delivery status read against the selected database composition. */
@NamedInterface(value = "fulfillment-tenant-composition", propagate = false)
public interface BuyerDeliveryTrackingRequestRunner {
    <T> T execute(CurrentAccessContext context, Function<BuyerDeliveryTrackingQueries, T> query);
}
