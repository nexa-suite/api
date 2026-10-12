package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects Tenant-only manual Sales Order writes when the local capability is enabled. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundManualSalesOrderConfiguration {
    @Bean
    @Primary
    ManualSalesOrderUseCase tenantBoundManualSalesOrderUseCase(TenantBusinessDatabaseRouter router,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundManualSalesOrderUseCase(router, compositions);
    }
}
