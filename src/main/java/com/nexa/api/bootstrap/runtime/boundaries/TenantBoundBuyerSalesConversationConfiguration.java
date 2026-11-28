package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.port.in.BuyerSalesConversationUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantBuyerSalesMessagePersistenceFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Enables tenant-routed HTTP message polling for local or explicitly enabled cloud Tenant routing. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "tenant-cloud"})
@ConditionalOnExpression("${nexa.tenant-business.purchase-request.enabled:false} or "
        + "${nexa.tenant-business.cloud-buyer-sales-messaging.enabled:false}")
public class TenantBoundBuyerSalesConversationConfiguration {
    @Bean
    BuyerSalesConversationUseCase tenantBoundBuyerSalesConversationUseCase(TenantBusinessDatabaseRouter router,
            TenantBuyerSalesMessagePersistenceFactory persistence) {
        return new TenantBoundBuyerSalesConversationUseCase(router, persistence);
    }
}
