package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Routes the existing fulfillment, dispatch and receipt use cases through the cloud Tenant binding. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-cloud")
@ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-mobile-fulfillment", name = "enabled", havingValue = "true")
public class CloudMobileFulfillmentConfiguration {
    @Bean
    Route cloudMobileFulfillmentRoute(TenantBusinessDatabaseRouter router,
            TenantFulfillmentDeliveryCompositionProviderAdapter compositions,
            WarehouseObjectAccess warehouses, WorkforceDirectory workforce,
            @Value("${nexa.tenant-business.cloud-mobile-fulfillment.buyer-location-enabled:false}") boolean buyerLocationEnabled,
            Environment environment) {
        if (buyerLocationEnabled && !environment.getProperty("nexa.scheduling.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Cloud driver location requires scheduled memory expiration");
        }
        return new Route(new TenantBoundFulfillmentDeliveryRequestRunner(router, compositions, warehouses, workforce,
                false, buyerLocationEnabled));
    }

    record Route(FulfillmentDeliveryRequestRunner commands) { }
}
