package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.publicapi.BuyerWalletStoreUnavailableException;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.payments.tenantdatabase.BuyerWalletTenantDatabaseAdapterFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies one explicit Tenant-only wallet reader or a closed unavailable port. */
@Configuration(proxyBeanMethods = false)
public class TenantBoundBuyerWalletReadConfiguration {
    @Bean
    BuyerWalletReadPort buyerWalletReadPort(
            @Value("${nexa.tenant-business.buyer-wallet-read.enabled:false}") boolean enabled,
            ObjectProvider<TenantBusinessDatabaseRouter> router,
            ObjectProvider<TenantCustomerAccountQueryFactory> customerAccounts,
            ObjectProvider<TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver> expiryPolicies,
            ObjectProvider<TenantSalesCommitmentCompositionProvider> salesCommitments,
            ObjectProvider<MeterRegistry> metrics,
            BuyerWalletTenantDatabaseAdapterFactory walletAdapters) {
        if (!enabled) return unavailable();
        TenantBusinessDatabaseRouter availableRouter = router.getIfAvailable();
        TenantCustomerAccountQueryFactory availableCustomerAccounts = customerAccounts.getIfAvailable();
        if (availableRouter == null || availableCustomerAccounts == null) return unavailable();
        return new TenantBoundBuyerWalletReadPort(availableRouter, availableCustomerAccounts, walletAdapters,
                expiryPolicies.getIfAvailable(), salesCommitments.getIfAvailable(), metrics.getIfAvailable());
    }

    private static BuyerWalletReadPort unavailable() {
        return (context, humanIdentityId, page, size) -> {
            throw new BuyerWalletStoreUnavailableException();
        };
    }
}
