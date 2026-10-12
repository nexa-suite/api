package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.Disposition;
import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.DispositionResult;
import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.Reading;
import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.ReadingCommand;
import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.Snapshot;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;

/** Delivery temperature evidence and execution-hold disposition use cases. */
public interface ExecutionTemperatureOperations {
    Snapshot read(CurrentAccessContext context, UUID deliveryId, boolean driver);

    Reading record(CurrentAccessContext context, UUID deliveryId, long expectedDeliveryVersion,
                   String idempotencyKey, ReadingCommand command);

    DispositionResult dispose(CurrentAccessContext context, UUID deliveryId, UUID holdId,
                              long expectedDeliveryVersion, String idempotencyKey,
                              Disposition disposition, String reason);
}
