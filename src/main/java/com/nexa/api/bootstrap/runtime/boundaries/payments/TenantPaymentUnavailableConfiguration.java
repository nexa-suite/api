package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.boundaries.TenantPaymentServiceBindingsFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Keeps payment HTTP and webhook requests off central business persistence while Tenant routing is disabled. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class TenantPaymentUnavailableConfiguration {
    @Bean
    @Primary
    TenantPaymentUnavailableAdapter tenantPaymentUnavailableAdapter(
            ObjectProvider<TenantPaymentUnavailableAdapter.BuyerReceivableRoutes> buyerReceivables) {
        return new TenantPaymentUnavailableAdapter(buyerReceivables.getIfAvailable());
    }

    @Bean
    @Profile("tenant-cloud")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-journey-read", name = "enabled", havingValue = "true")
    TenantPaymentUnavailableAdapter.BuyerReceivableRoutes cloudBuyerReceivableRoutes(
            TenantBusinessDatabaseRouter router, TenantPaymentServiceBindingsFactory services) {
        return new TenantPaymentUnavailableAdapter.BuyerReceivableRoutes(router, services);
    }
}
