package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Guards the retained central Fulfillment runner when physical Tenant routing is not selected. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@Conditional(CentralFulfillmentFallbackGuardConfiguration.CentralFulfillmentRouteSelected.class)
class CentralFulfillmentFallbackGuardConfiguration {
    @Bean
    @Primary
    FulfillmentDeliveryRequestRunner centralFallbackFulfillmentDeliveryRequestRunner(
            @Qualifier("localFulfillmentDeliveryRequestRunner") FulfillmentDeliveryRequestRunner central,
            CentralTenantBusinessDataAccessGuard guard,
            ObjectProvider<CloudMobileFulfillmentConfiguration.Route> cloud) {
        var route = cloud.getIfAvailable();
        if (route != null) return route.commands();
        return new CentralFallbackFulfillmentDeliveryRequestRunner(central, guard);
    }

    static final class CentralFulfillmentRouteSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean localRouteEnabled = context.getEnvironment().acceptsProfiles(Profiles.of("local"))
                    && context.getEnvironment().getProperty(
                            "nexa.tenant-business.fulfillment-delivery.enabled", Boolean.class, false);
            return !localRouteEnabled;
        }
    }
}
