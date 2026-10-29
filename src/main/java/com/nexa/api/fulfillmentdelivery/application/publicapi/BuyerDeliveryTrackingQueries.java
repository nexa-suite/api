package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.ReceiptView;

public interface BuyerDeliveryTrackingQueries {
    BuyerDeliveryTrackingModels.Page<BuyerDeliveryTrackingModels.DeliveryView> list(
            CurrentAccessContext context, int page, int size);
    BuyerDeliveryTrackingModels.DeliveryView detail(CurrentAccessContext context, UUID deliveryId);
    List<BuyerDeliveryTrackingModels.EventView> events(CurrentAccessContext context, UUID deliveryId);
    List<ReceiptView> receipts(CurrentAccessContext context, UUID deliveryId);
}
