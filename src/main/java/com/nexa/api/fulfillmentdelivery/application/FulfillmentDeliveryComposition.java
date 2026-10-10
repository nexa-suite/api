package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.fulfillmentdelivery.application.service.BuyerDeliveryTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentWorkListService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchReadinessService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentDriverAssignmentService;
import com.nexa.api.fulfillmentdelivery.application.service.OutgoingGoodsCheckService;

import java.util.Objects;

/** Application services bound to one database transaction for a BC-06 request. */
public record FulfillmentDeliveryComposition(
        FulfillmentLifecycleService fulfillment,
        FulfillmentWorkListService workList,
        OutgoingGoodsCheckService outgoingGoodsChecks,
        DriverDeliveryService driverDeliveries,
        DriverTrackingService driverTracking,
        BuyerDeliveryTrackingService buyerTracking,
        LogisticsOperationsService logistics,
        DispatchReadinessService dispatchReadiness,
        FulfillmentDriverAssignmentService driverAssignments) {
    public FulfillmentDeliveryComposition {
        Objects.requireNonNull(fulfillment, "Fulfillment service is required");
        Objects.requireNonNull(workList, "Fulfillment work list is required");
        Objects.requireNonNull(outgoingGoodsChecks, "Outgoing goods checks are required");
        Objects.requireNonNull(driverDeliveries, "Driver delivery service is required");
        Objects.requireNonNull(driverTracking, "Driver tracking service is required");
        Objects.requireNonNull(buyerTracking, "Buyer tracking service is required");
        Objects.requireNonNull(logistics, "Logistics service is required");
        Objects.requireNonNull(dispatchReadiness, "Dispatch readiness service is required");
        Objects.requireNonNull(driverAssignments, "Fulfillment driver assignment service is required");
    }
}
