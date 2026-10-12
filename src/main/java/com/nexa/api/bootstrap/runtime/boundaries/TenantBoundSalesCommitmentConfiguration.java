package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.salescommitment.application.port.PurchaseRequestDraftPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects Tenant-only BC-04 entrypoints when the local capability is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundSalesCommitmentConfiguration {
    @Bean
    @Primary
    PurchaseRequestDraftPort tenantBoundPurchaseRequestDraftPort(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundPurchaseRequestDraftPort(router, expiryPolicies, compositions);
    }

    @Bean
    @Primary
    PurchaseRequestUseCase tenantBoundPurchaseRequestUseCase(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundPurchaseRequestUseCase(router, expiryPolicies, compositions);
    }

    @Bean
    @Primary
    SalesOrderUseCase tenantBoundSalesOrderUseCase(TenantBusinessDatabaseRouter router,
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundSalesOrderUseCase(router, expiryPolicies, compositions);
    }

    @Bean
    @Primary
    DirectOrderUseCase tenantBoundDirectOrderUseCase(TenantBusinessDatabaseRouter router,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundDirectOrderUseCase(router, compositions);
    }
}
