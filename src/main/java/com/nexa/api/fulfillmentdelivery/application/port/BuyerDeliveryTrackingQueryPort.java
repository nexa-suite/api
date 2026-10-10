package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.DeliveryRecord;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.EventView;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.Page;

import java.util.List;
import java.util.UUID;

public interface BuyerDeliveryTrackingQueryPort {
    Page<DeliveryRecord> list(UUID tenantId, UUID workspaceId, UUID clientAccountId, int page, int size);

    DeliveryRecord detail(UUID tenantId, UUID workspaceId, UUID deliveryId);

    List<EventView> events(UUID tenantId, UUID workspaceId, UUID deliveryId);
}
