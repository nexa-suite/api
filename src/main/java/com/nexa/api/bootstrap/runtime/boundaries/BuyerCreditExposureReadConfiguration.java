package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.creditreceivables.application.exception.CreditReceivablesUnavailableException;
import com.nexa.api.creditreceivables.application.publicapi.BuyerCreditExposureReader;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureUseCase;
import com.nexa.api.creditreceivables.tenantdatabase.TenantBuyerCreditExposureReaderFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class BuyerCreditExposureReadConfiguration {
    @Bean
    BuyerCreditExposureReader buyerCreditExposureReads(CreditExposureUseCase service) {
        return service::readBuyer;
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("tenant-cloud")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-journey-read", name = "enabled",
            havingValue = "true")
    static class CloudReads {
        @Bean
        @Primary
        BuyerCreditExposureReader cloudBuyerCreditExposureReads(TenantBusinessDatabaseRouter router,
                TenantBuyerCreditExposureReaderFactory credit, TenantCustomerAccountQueryFactory customerAccounts) {
            return (context, currency) -> {
                context.requireSurface(Surface.PORTAL);
                context.requirePermission(PermissionKey.PAYMENT_READ);
                try {
                    return router.inReadOnlyTransaction(context, jdbc -> {
                        var accounts = customerAccounts.bindTo(jdbc);
                        return credit.bindBuyerExposureTo(jdbc, accounts, TenantCreditEligibilitySourceFactory.bindTo(accounts))
                                .readBuyer(context, currency);
                    });
                } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                         | DataAccessException | CannotCreateTransactionException unavailable) {
                    throw new CreditReceivablesUnavailableException(unavailable);
                }
            };
        }
    }
}
