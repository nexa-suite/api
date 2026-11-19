package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.publicapi.BuyerDeliveryTrackingQueries;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantBuyerDeliveryTrackingQueryFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.function.Function;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class BuyerDeliveryTrackingReadConfiguration {
    @Bean
    BuyerDeliveryTrackingRequestRunner buyerDeliveryTrackingReads(FulfillmentDeliveryRequestRunner requests) {
        return new BuyerDeliveryTrackingRequestRunner() {
            @Override
            public <T> T execute(CurrentAccessContext context, Function<BuyerDeliveryTrackingQueries, T> query) {
                return requests.execute(context, FulfillmentDeliveryRequestRunner.Requirements.none(),
                        composition -> query.apply(composition.buyerTracking()));
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("tenant-cloud")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-journey-read", name = "enabled",
            havingValue = "true")
    static class CloudReads {
        @Bean
        @Primary
        BuyerDeliveryTrackingRequestRunner cloudBuyerDeliveryTrackingReads(
                TenantBusinessDatabaseRouter router,
                TenantBuyerDeliveryTrackingQueryFactory tracking,
                TenantCustomerAccountQueryFactory accounts,
                TenantSalesOrderFulfillmentQueryFactory orders) {
            return new BuyerDeliveryTrackingRequestRunner() {
                @Override
                public <T> T execute(CurrentAccessContext context, Function<BuyerDeliveryTrackingQueries, T> query) {
                    context.requirePermission(PermissionKey.BUYER_TRACKING_READ);
                    try {
                        return router.inReadOnlyTransaction(context, jdbc -> query.apply(
                                tracking.bindBuyerTracking(jdbc, accounts.bindTo(jdbc), orders.bindTo(jdbc))));
                    } catch (TenantBusinessDatabaseSchemaManifestMismatchException
                             | CannotGetJdbcConnectionException | CannotCreateTransactionException unavailable) {
                        throw new TenantBusinessDatabaseUnavailableException(
                                "Tenant fulfillment data is temporarily unavailable", unavailable);
                    }
                }
            };
        }
    }
}
