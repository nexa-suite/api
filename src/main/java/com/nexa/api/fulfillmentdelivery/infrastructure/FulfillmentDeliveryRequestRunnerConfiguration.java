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
            FulfillmentDriverAssignmentService driverAssignments) {
        return new LocalFulfillmentDeliveryRequestRunner(fulfillment, workList, outgoingGoodsChecks,
                driverDeliveries, driverTracking, buyerTracking, logistics, dispatchReadiness, driverAssignments);
    }
}
