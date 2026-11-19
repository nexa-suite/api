package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.salescommitment.application.model.SalesCommitmentQueryRunner;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestQueries;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderQueries;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory.Composition;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.function.Function;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesCommitmentQueryConfiguration {
    @Bean
    SalesCommitmentQueryRunner salesCommitmentQueries(PurchaseRequestUseCase purchaseRequests,
            SalesOrderUseCase salesOrders, ObjectProvider<CloudBuyerSalesReads> cloudBuyerReads) {
        var cloud = cloudBuyerReads.getIfAvailable();
        return new SalesCommitmentQueryRunner() {
            @Override
            public <T> T purchaseRequests(CurrentAccessContext context, Function<PurchaseRequestQueries, T> query) {
                if (cloud == null || !isPortalBuyer(context)) {
                    return query.apply(purchaseRequests);
                }
                context.requirePermission(PermissionKey.BUYER_SALES_READ);
                return cloud.execute(context, composition -> query.apply(composition.purchaseRequests()));
            }

            @Override
            public <T> T salesOrders(CurrentAccessContext context, Function<SalesOrderQueries, T> query) {
                if (cloud == null || !isPortalBuyer(context) || context.allows(PermissionKey.SALES_ORDER_READ)) {
                    return query.apply(salesOrders);
                }
                context.requirePermission(PermissionKey.BUYER_ORDER_READ);
                return cloud.execute(context, composition -> query.apply(composition.salesOrders()));
            }
        };
    }

    private static boolean isPortalBuyer(CurrentAccessContext context) {
        return context.surface() == Surface.PORTAL && context.hasRole(MembershipRole.BUYER);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("tenant-cloud")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-journey-read", name = "enabled",
            havingValue = "true")
    static class CloudReads {
        @Bean
        CloudBuyerSalesReads cloudBuyerSalesReads(TenantBusinessDatabaseRouter router,
                TenantSalesCommitmentCompositionProvider provider) {
            return new CloudBuyerSalesReads(router, provider);
        }
    }

    record CloudBuyerSalesReads(TenantBusinessDatabaseRouter router, TenantSalesCommitmentCompositionProvider provider) {
        <T> T execute(CurrentAccessContext context, Function<Composition, T> query) {
            try {
                return router.inReadOnlyTransaction(context, jdbc -> query.apply(provider.bindTo(jdbc)));
            } catch (TenantBusinessDatabaseSchemaManifestMismatchException | CannotGetJdbcConnectionException
                     | CannotCreateTransactionException unavailable) {
                throw new TenantBusinessDatabaseUnavailableException("Tenant sales data is temporarily unavailable", unavailable);
            }
        }
    }
}
