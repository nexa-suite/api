package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.*;
import java.time.Instant;
import java.util.UUID;

public interface DriverTrackingPort {
    Workday current(UUID tenant, UUID workspace, UUID actor);
    Workday command(WorkdayCommand request);
    Coordinate capture(SampleCommand request);
    Coordinate latest(UUID tenant, UUID workspace, UUID actor, Instant now);
    DeliveryScope deliveryScope(UUID tenant, UUID workspace, UUID deliveryId);
    void purgeExpiredCoordinates();
}
