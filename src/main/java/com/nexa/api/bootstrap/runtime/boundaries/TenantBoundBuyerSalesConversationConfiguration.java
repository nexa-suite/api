package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerSalesConversationUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerSalesMessagePersistenceFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Enables tenant-routed HTTP message polling only with the local Tenant business database capability. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundBuyerSalesConversationConfiguration {
    @Bean
    BuyerSalesConversationUseCase tenantBoundBuyerSalesConversationUseCase(TenantBusinessDatabaseRouter router,
            TenantBuyerSalesMessagePersistenceFactory persistence) {
        return new TenantBoundBuyerSalesConversationUseCase(router, persistence);
    }
}
