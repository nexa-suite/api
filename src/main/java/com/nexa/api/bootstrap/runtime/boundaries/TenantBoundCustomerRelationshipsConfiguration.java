package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Routes BC-02 customer, address, and field-visit HTTP use cases to the local Tenant database. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundCustomerRelationshipsConfiguration {
    @Bean
    @Primary
    TenantBoundCustomerRelationshipsUseCase tenantCustomerRelationshipsUseCase(
            TenantBusinessDatabaseRouter router,
            TenantCustomerRelationshipsCompositionFactory compositions,
            BuyerMembershipDirectory buyerMemberships) {
        return new TenantBoundCustomerRelationshipsUseCase(router, compositions, buyerMemberships);
    }
}
