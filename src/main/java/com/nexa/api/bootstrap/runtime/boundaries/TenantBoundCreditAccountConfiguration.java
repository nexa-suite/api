package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.creditreceivables.application.publicapi.CreditAccountConfigurationUseCase;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountDirectoryQueryFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Enables credit configuration only with the local Tenant business-database capability. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundCreditAccountConfiguration {
    @Bean
    @Primary
    CreditAccountConfigurationUseCase tenantCreditAccountConfigurationUseCase(
            TenantBusinessDatabaseRouter router,
            TenantCustomerAccountDirectoryQueryFactory accountDirectory,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings) {
        return new TenantBoundCreditAccountConfigurationUseCase(router, accountDirectory,
                creditAdapters, traceabilityBindings);
    }
}
