package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.service.BuyerDeliveryTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchReadinessService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentDriverAssignmentService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentWorkListService;
import com.nexa.api.fulfillmentdelivery.application.service.OutgoingGoodsCheckService;
import com.nexa.api.fulfillmentdelivery.application.service.MobileDeliveryContractService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchWindowPlanService;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryLoadService;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryInstructionService;
import com.nexa.api.fulfillmentdelivery.application.service.ExecutionTemperatureService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryIncidentService;
import com.nexa.api.fulfillmentdelivery.application.service.OperationalExceptionService;
import com.nexa.api.fulfillmentdelivery.application.service.BomOperationalExceptionService;
import com.nexa.api.fulfillmentdelivery.application.service.CustomerInstructionService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;
import java.util.function.Function;

/** Preserves the existing central-backed composition when Tenant routing is not opted in. */
public final class LocalFulfillmentDeliveryRequestRunner implements FulfillmentDeliveryRequestRunner {
    private final FulfillmentDeliveryComposition composition;

    public LocalFulfillmentDeliveryRequestRunner(FulfillmentLifecycleService fulfillment,
                                                 FulfillmentWorkListService workList,
                                                 OutgoingGoodsCheckService outgoingGoodsChecks,
                                                 DriverDeliveryService driverDeliveries,
                                                 DriverTrackingService driverTracking,
                                                 BuyerDeliveryTrackingService buyerTracking,
                                                 LogisticsOperationsService logistics,
                                                 DispatchReadinessService dispatchReadiness,
                                                 FulfillmentDriverAssignmentService driverAssignments,
                                                 MobileDeliveryContractService mobileDeliveryContracts,
                                                 DispatchWindowPlanService dispatchWindowPlans,
                                                 DeliveryLoadService deliveryLoads,
                                                 DeliveryInstructionService deliveryInstructions,
                                                 ExecutionTemperatureService executionTemperatures,
                                                 DriverDeliveryIncidentService driverDeliveryIncidents,
                                                 OperationalExceptionService operationalExceptions,
                                                 BomOperationalExceptionService bomOperationalExceptions,
                                                 CustomerInstructionService customerInstructions) {
        this.composition = new FulfillmentDeliveryComposition(fulfillment, workList, outgoingGoodsChecks,
                driverDeliveries, driverTracking, buyerTracking, logistics, dispatchReadiness, driverAssignments,
                (context, reservationId, version, key) -> logistics.create(context, reservationId, version, key),
                mobileDeliveryContracts, dispatchWindowPlans, deliveryLoads, deliveryInstructions,
                executionTemperatures, driverDeliveryIncidents, operationalExceptions,
                bomOperationalExceptions, customerInstructions);
    }

    @Override
    public <T> T execute(CurrentAccessContext context, Requirements requirements,
                         Function<FulfillmentDeliveryComposition, T> work) {
        Objects.requireNonNull(context, "Verified access context is required");
        Objects.requireNonNull(requirements, "Preflight requirements are required");
        return Objects.requireNonNull(work, "BC-06 request work is required").apply(composition);
    }
}
