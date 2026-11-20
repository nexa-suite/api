package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

/** Selects authorized customer relationship routes for the cloud profile. */
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

    @Bean
    @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-field-visits", name = "enabled", havingValue = "true")
    CloudFieldVisits cloudFieldVisits(TenantBusinessDatabaseRouter router,
            TenantCustomerRelationshipsCompositionFactory compositions,
            BuyerMembershipDirectory buyerMemberships, ActiveSalesMembershipDirectory salesMemberships,
            TenantBusinessTraceabilityBindingsFactory traceability) {
        return new CloudFieldVisits(new TenantBoundCustomerRelationshipsUseCase(
                router, compositions, buyerMemberships, salesMemberships, traceability));
    }

    record CloudFieldVisits(FieldVisitUseCase delegate) implements FieldVisitUseCase {
        @Override
        public FieldVisitEvidence record(CurrentAccessContext context, String customerId, long version,
                String idempotencyKey, Command command) {
            return delegate.record(context, customerId, version, idempotencyKey, command);
        }

        @Override
        public List<FieldVisitEvidence> listForCustomer(CurrentAccessContext context, String customerId) {
            return delegate.listForCustomer(context, customerId);
        }
    }
}
