package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.salescommitment.application.policy.MinimumDeliveryLeadTime;
import com.nexa.api.salescommitment.application.policy.MinimumDeliveryLeadTimePolicyProvider;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Publishes the verified current-Workspace delivery policy to central Sales runtime use cases. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TenantBusinessDatabaseDeliveryLeadTimePolicyProviderConfiguration {
    @Bean
    MinimumDeliveryLeadTimePolicyProvider salesDeliveryLeadTimePolicyProvider(
            OperationalSettingsAccess operationalSettings) {
        TenantBusinessDatabaseDeliveryLeadTimePolicyResolver resolver =
                new TenantBusinessDatabaseDeliveryLeadTimePolicyResolver(operationalSettings);
        return context -> new MinimumDeliveryLeadTime(resolver.minimumDeliveryLeadDays(context));
    }
}
