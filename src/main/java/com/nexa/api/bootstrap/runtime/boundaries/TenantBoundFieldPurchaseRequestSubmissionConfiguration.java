package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects the callback-bound field submission when local Tenant persistence is enabled. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundFieldPurchaseRequestSubmissionConfiguration {
    @Bean
    @Primary
    FieldPurchaseRequestSubmissionUseCase tenantBoundFieldPurchaseRequestSubmissionUseCase(
            TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver expiryPolicies,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundFieldPurchaseRequestSubmissionUseCase(expiryPolicies, compositions);
    }
}
