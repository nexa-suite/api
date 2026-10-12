package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/** Routes credit exposure and financial adjustment HTTP use cases through the local Tenant database. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class TenantBoundCreditReceivablesConfiguration {
    @Bean
    @Primary
    TenantBoundCreditReceivablesUseCase tenantCreditReceivablesUseCase(
            TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantPaymentConfirmationQueryFactory paymentConfirmations,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            Clock clock) {
        return new TenantBoundCreditReceivablesUseCase(router, customerAccounts, creditAdapters,
                salesOrders, paymentConfirmations, traceabilityBindings, clock);
    }
}
