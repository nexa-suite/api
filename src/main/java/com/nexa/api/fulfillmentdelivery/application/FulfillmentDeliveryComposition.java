package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchWorkflowCommands;
import com.nexa.api.fulfillmentdelivery.application.publicapi.BuyerDeliveryTrackingQueries;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchReadinessQueries;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DriverDeliveryOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DriverTrackingOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDriverAssignmentOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentLifecycleOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentWorkListQueries;
import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.MobileDeliveryContractOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.OutgoingGoodsCheckOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchWindowPlanOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DeliveryLoadOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DeliveryInstructionOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.ExecutionTemperatureOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DriverDeliveryIncidentOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.OperationalExceptionOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.BomOperationalExceptionOperations;
import com.nexa.api.fulfillmentdelivery.application.publicapi.CustomerInstructionOperations;

import java.util.Objects;

/** Application services bound to one database transaction for a BC-06 request. */
public record FulfillmentDeliveryComposition(
        FulfillmentLifecycleOperations fulfillment,
        FulfillmentWorkListQueries workList,
        OutgoingGoodsCheckOperations outgoingGoodsChecks,
        DriverDeliveryOperations driverDeliveries,
        DriverTrackingOperations driverTracking,
        BuyerDeliveryTrackingQueries buyerTracking,
        LogisticsOperations logistics,
        DispatchReadinessQueries dispatchReadiness,
        FulfillmentDriverAssignmentOperations driverAssignments,
        DispatchWorkflowCommands dispatchWorkflowCommands,
        MobileDeliveryContractOperations mobileDeliveryContracts,
        DispatchWindowPlanOperations dispatchWindowPlans,
        DeliveryLoadOperations deliveryLoads,
        DeliveryInstructionOperations deliveryInstructions,
        ExecutionTemperatureOperations executionTemperatures,
        DriverDeliveryIncidentOperations driverDeliveryIncidents,
        OperationalExceptionOperations operationalExceptions,
        BomOperationalExceptionOperations bomOperationalExceptions,
        CustomerInstructionOperations customerInstructions) {
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
        Objects.requireNonNull(dispatchWorkflowCommands, "Dispatch workflow commands are required");
        Objects.requireNonNull(mobileDeliveryContracts, "Mobile delivery contract operations are required");
        Objects.requireNonNull(dispatchWindowPlans, "Dispatch window plan operations are required");
        Objects.requireNonNull(deliveryLoads, "Delivery load operations are required");
        Objects.requireNonNull(deliveryInstructions, "Delivery instruction operations are required");
        Objects.requireNonNull(executionTemperatures, "Execution temperature operations are required");
        Objects.requireNonNull(driverDeliveryIncidents, "Driver delivery incident operations are required");
        Objects.requireNonNull(operationalExceptions, "Operational exception operations are required");
        Objects.requireNonNull(bomOperationalExceptions, "Business operations exception operations are required");
        Objects.requireNonNull(customerInstructions, "Customer instruction operations are required");
    }
}
