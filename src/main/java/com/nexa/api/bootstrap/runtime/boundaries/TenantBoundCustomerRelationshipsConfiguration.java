package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountAddressPersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountPersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantFieldVisitPersistenceFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

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
            TenantClientAccountPersistenceFactory accountPersistence,
            TenantCustomerAccountQueryFactory accountQueries,
            TenantClientAccountAddressPersistenceFactory addressPersistence,
            TenantFieldVisitPersistenceFactory fieldVisitPersistence,
            BuyerMembershipDirectory buyerMemberships,
            Clock clock,
            ObjectMapper mapper) {
        return new TenantBoundCustomerRelationshipsUseCase(router, accountPersistence, accountQueries,
                addressPersistence, fieldVisitPersistence, buyerMemberships, clock, mapper);
    }
}
