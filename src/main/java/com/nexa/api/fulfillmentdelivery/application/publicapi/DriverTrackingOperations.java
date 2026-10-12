package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.UUID;

public interface DriverTrackingOperations {
    DriverTrackingModels.Workday current(CurrentAccessContext context);
    DriverTrackingModels.Workday command(CurrentAccessContext context, UUID day, Long version,
                                         String action, String idempotencyKey);
    DriverTrackingModels.Coordinate capture(CurrentAccessContext context, UUID day, UUID sampleId,
                                            double latitude, double longitude, double accuracyMeters,
                                            Instant capturedAt);
    DriverTrackingModels.Coordinate ownLocation(CurrentAccessContext context);
    DriverTrackingModels.Coordinate dispatchLocation(CurrentAccessContext context, UUID driverMembershipId);
    DriverTrackingModels.DeliveryTracking buyerLocation(CurrentAccessContext context, UUID deliveryId);
}
