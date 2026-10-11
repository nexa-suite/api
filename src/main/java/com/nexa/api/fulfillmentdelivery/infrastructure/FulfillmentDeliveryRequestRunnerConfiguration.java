package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.service.BuyerDeliveryTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentWorkListService;
import com.nexa.api.fulfillmentdelivery.application.service.OutgoingGoodsCheckService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchReadinessService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentDriverAssignmentService;
import com.nexa.api.fulfillmentdelivery.application.service.MobileDeliveryContractService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchWindowPlanService;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryLoadService;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryInstructionService;
import com.nexa.api.fulfillmentdelivery.application.service.ExecutionTemperatureService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryIncidentService;
import com.nexa.api.fulfillmentdelivery.application.service.OperationalExceptionService;
import com.nexa.api.fulfillmentdelivery.application.service.BomOperationalExceptionService;
import com.nexa.api.fulfillmentdelivery.application.service.CustomerInstructionService;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.fulfillment-delivery", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class FulfillmentDeliveryRequestRunnerConfiguration {
    @Bean
    FulfillmentDeliveryRequestRunner localFulfillmentDeliveryRequestRunner(
            FulfillmentLifecycleService fulfillment, FulfillmentWorkListService workList,
            OutgoingGoodsCheckService outgoingGoodsChecks, DriverDeliveryService driverDeliveries,
            DriverTrackingService driverTracking, BuyerDeliveryTrackingService buyerTracking,
            LogisticsOperationsService logistics, DispatchReadinessService dispatchReadiness,
            FulfillmentDriverAssignmentService driverAssignments,
            MobileDeliveryContractService mobileDeliveryContracts,
            DispatchWindowPlanService dispatchWindowPlans, DeliveryLoadService deliveryLoads,
            DeliveryInstructionService deliveryInstructions, ExecutionTemperatureService executionTemperatures,
            DriverDeliveryIncidentService driverDeliveryIncidents, OperationalExceptionService operationalExceptions,
            BomOperationalExceptionService bomOperationalExceptions, CustomerInstructionService customerInstructions) {
        return new LocalFulfillmentDeliveryRequestRunner(fulfillment, workList, outgoingGoodsChecks,
                driverDeliveries, driverTracking, buyerTracking, logistics, dispatchReadiness, driverAssignments,
                mobileDeliveryContracts, dispatchWindowPlans, deliveryLoads, deliveryInstructions,
                executionTemperatures, driverDeliveryIncidents, operationalExceptions,
                bomOperationalExceptions, customerInstructions);
    }
}
