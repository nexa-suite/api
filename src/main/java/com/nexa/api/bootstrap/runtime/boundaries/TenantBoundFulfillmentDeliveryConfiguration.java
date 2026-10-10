package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects Tenant-only BC-06 execution only when the local capability is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.fulfillment-delivery", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundFulfillmentDeliveryConfiguration {
    @Bean
    @Primary
    FulfillmentDeliveryRequestRunner tenantBoundFulfillmentDeliveryRequestRunner(
            TenantBusinessDatabaseRouter router,
            TenantFulfillmentDeliveryCompositionFactory compositions,
            WarehouseObjectAccess warehouseAccess,
            WorkforceDirectory workforce) {
        return new TenantBoundFulfillmentDeliveryRequestRunner(router, compositions,
                warehouseAccess, workforce);
    }
}
