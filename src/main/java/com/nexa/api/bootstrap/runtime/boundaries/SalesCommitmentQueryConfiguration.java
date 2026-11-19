package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
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
            SalesOrderUseCase salesOrders, ObjectProvider<CloudSalesReads> cloudReads) {
        var cloud = cloudReads.getIfAvailable();
        return new SalesCommitmentQueryRunner() {
            @Override
            public <T> T purchaseRequests(CurrentAccessContext context, Function<PurchaseRequestQueries, T> query) {
                if (cloud == null) {
                    return query.apply(purchaseRequests);
                }
                if (cloud.buyer(context)) {
                    context.requirePermission(PermissionKey.BUYER_SALES_READ);
                } else if (cloud.sales(context)) {
                    context.requirePermission(PermissionKey.SALES_PURCHASE_REQUEST_READ);
                } else {
                    return query.apply(purchaseRequests);
                }
                return cloud.execute(context, composition -> query.apply(composition.purchaseRequests()), true);
            }

            @Override
            public <T> T salesOrders(CurrentAccessContext context, Function<SalesOrderQueries, T> query) {
                if (cloud != null && cloud.sales(context)) {
                    context.requirePermission(PermissionKey.SALES_ORDER_READ);
                    return cloud.execute(context, composition -> query.apply(composition.salesOrders()), false);
                }
                if (cloud != null && cloud.buyer(context) && !context.allows(PermissionKey.SALES_ORDER_READ)) {
                    context.requirePermission(PermissionKey.BUYER_ORDER_READ);
                    return cloud.execute(context, composition -> query.apply(composition.salesOrders()), true);
                }
                return query.apply(salesOrders);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("tenant-cloud")
    @Conditional(CloudReadsEnabled.class)
    static class CloudReads {
        @Bean
        CloudSalesReads cloudSalesReads(TenantBusinessDatabaseRouter router,
                TenantSalesCommitmentCompositionProvider provider,
                @Value("${nexa.tenant-business.cloud-buyer-journey-read.enabled:false}") boolean buyerReads,
                @Value("${nexa.tenant-business.cloud-operations-read.enabled:false}") boolean operationsReads) {
            return new CloudSalesReads(router, provider, buyerReads, operationsReads);
        }
    }

    static final class CloudReadsEnabled extends AnyNestedCondition {
        CloudReadsEnabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-journey-read", name = "enabled",
                havingValue = "true")
        static class Buyer { }

        @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-operations-read", name = "enabled",
                havingValue = "true")
        static class Operations { }
    }

    record CloudSalesReads(TenantBusinessDatabaseRouter router, TenantSalesCommitmentCompositionProvider provider,
                           boolean buyerReads, boolean operationsReads) {
        boolean buyer(CurrentAccessContext context) {
            return buyerReads && context.surface() == Surface.PORTAL && context.hasRole(MembershipRole.BUYER);
        }

        boolean sales(CurrentAccessContext context) {
            return operationsReads && context.surface() == Surface.PLATFORM && context.hasRole(MembershipRole.SALES);
        }

        <T> T execute(CurrentAccessContext context, Function<Composition, T> query, boolean readOnly) {
            TenantBusinessDatabaseWork<T> work = jdbc -> query.apply(provider.bindTo(jdbc));
            try {
                // Sales order queries hold the canonical account assignment lock through the projection.
                return readOnly ? router.inReadOnlyTransaction(context, work) : router.inTransaction(context, work);
            } catch (TenantBusinessDatabaseSchemaManifestMismatchException | CannotGetJdbcConnectionException
                     | CannotCreateTransactionException unavailable) {
                throw new TenantBusinessDatabaseUnavailableException("Tenant sales data is temporarily unavailable", unavailable);
            }
        }
    }
}
