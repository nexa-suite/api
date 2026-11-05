package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects existing authorized Tenant account reads for the explicit cloud delivery. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-cloud")
@ConditionalOnProperty(prefix = "nexa.tenant-business", name = {
        "cloud-router.enabled", "cloud-read.enabled", "catalog-detail-read.enabled" }, havingValue = "true")
public class CloudTenantReadConfiguration {
    @Bean
    @Primary
    ClientAccountUseCase cloudBuyerSelfAccountRead(TenantBusinessDatabaseRouter router,
            TenantCustomerRelationshipsCompositionFactory compositions,
            BuyerMembershipDirectory buyerMemberships, ActiveSalesMembershipDirectory salesMemberships,
            TenantBusinessTraceabilityBindingsFactory traceability) {
        return new CloudBuyerSelfAccountReadUseCase(new TenantBoundCustomerRelationshipsUseCase(
                router, compositions, buyerMemberships, salesMemberships, traceability));
    }
}
