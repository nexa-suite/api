package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.publicapi.BuyerDeliveryTrackingQueries;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantBuyerDeliveryTrackingQueryFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuyerDeliveryTrackingReadConfigurationTests {
    private static final String ENABLED = "nexa.tenant-business.cloud-buyer-journey-read.enabled=true";
    private final FulfillmentDeliveryRequestRunner fulfillment = mock(FulfillmentDeliveryRequestRunner.class);
    private final TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
    private final TenantBuyerDeliveryTrackingQueryFactory tracking = mock(TenantBuyerDeliveryTrackingQueryFactory.class);
    private final TenantCustomerAccountQueryFactory accounts = mock(TenantCustomerAccountQueryFactory.class);
    private final TenantSalesOrderFulfillmentQueryFactory orders = mock(TenantSalesOrderFulfillmentQueryFactory.class);

    @Test
    void selectingCloudReadsRequiresCloudProfileAndExplicitOptIn() {
        CurrentAccessContext scope = mock(CurrentAccessContext.class);
        context("tenant-cloud").run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("cloudBuyerDeliveryTrackingReads");
            ctx.getBean(BuyerDeliveryTrackingRequestRunner.class).execute(scope, query -> query.list(scope, 0, 25));
            verify(fulfillment).execute(eq(scope), eq(FulfillmentDeliveryRequestRunner.Requirements.none()), any());
        });
        context("local").withPropertyValues(ENABLED).run(ctx ->
                assertThat(ctx).hasNotFailed().doesNotHaveBean("cloudBuyerDeliveryTrackingReads"));
        verify(router, never()).inReadOnlyTransaction(any(), any());
        verifyNoInteractions(tracking, accounts, orders);
    }

    @Test
    void bindsTrackingAndOwnerQueriesToOneReadOnlyTenantTransaction() {
        CurrentAccessContext scope = mock(CurrentAccessContext.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CustomerAccountQuery customerAccounts = mock(CustomerAccountQuery.class);
        SalesOrderFulfillmentQuery salesOrders = mock(SalesOrderFulfillmentQuery.class);
        BuyerDeliveryTrackingQueries buyerTracking = mock(BuyerDeliveryTrackingQueries.class);
        UUID deliveryId = UUID.randomUUID();

        when(router.inReadOnlyTransaction(eq(scope), any())).thenAnswer(call -> {
            TenantBusinessDatabaseWork<?> work = call.getArgument(1);
            return work.execute(jdbc);
        });
        when(accounts.bindTo(jdbc)).thenReturn(customerAccounts);
        when(orders.bindTo(jdbc)).thenReturn(salesOrders);
        when(tracking.bindBuyerTracking(jdbc, customerAccounts, salesOrders)).thenReturn(buyerTracking);

        context("tenant-cloud").withPropertyValues(ENABLED).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasBean("cloudBuyerDeliveryTrackingReads");
            ctx.getBean(BuyerDeliveryTrackingRequestRunner.class).execute(scope, query -> query.detail(scope, deliveryId));
            verify(scope).requirePermission(PermissionKey.BUYER_TRACKING_READ);
            verify(router).inReadOnlyTransaction(eq(scope), any());
            verify(buyerTracking).detail(scope, deliveryId);
            assertThat(ctx.getBean(FulfillmentDeliveryRequestRunner.class)).isSameAs(fulfillment);
            verifyNoInteractions(fulfillment);
        });
    }

    @Test
    void rejectsMissingPermissionBeforeRoutingAndNeverFallsBackOnUnavailableBinding() {
        CurrentAccessContext scope = mock(CurrentAccessContext.class);
        doThrow(new AccessPolicyViolation("denied")).when(scope).requirePermission(PermissionKey.BUYER_TRACKING_READ);
        context("tenant-cloud").withPropertyValues(ENABLED).run(ctx -> {
            var reads = ctx.getBean(BuyerDeliveryTrackingRequestRunner.class);
            assertThatThrownBy(() -> reads.execute(scope, query -> query.list(scope, 0, 25)))
                    .isInstanceOf(AccessPolicyViolation.class);
            verifyNoInteractions(router);

            CurrentAccessContext authorized = mock(CurrentAccessContext.class);
            when(router.inReadOnlyTransaction(eq(authorized), any()))
                    .thenThrow(new TenantBusinessDatabaseSchemaManifestMismatchException());
            assertThatThrownBy(() -> reads.execute(authorized, query -> query.list(authorized, 0, 25)))
                    .isInstanceOf(TenantBusinessDatabaseUnavailableException.class)
                    .hasCauseInstanceOf(TenantBusinessDatabaseSchemaManifestMismatchException.class);
            verifyNoInteractions(fulfillment, tracking, accounts, orders);
        });
    }

    private ApplicationContextRunner context(String profile) {
        return new ApplicationContextRunner().withUserConfiguration(BuyerDeliveryTrackingReadConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + profile)
                .withBean(FulfillmentDeliveryRequestRunner.class, () -> fulfillment)
                .withBean(TenantBusinessDatabaseRouter.class, () -> router)
                .withBean(TenantBuyerDeliveryTrackingQueryFactory.class, () -> tracking)
                .withBean(TenantCustomerAccountQueryFactory.class, () -> accounts)
                .withBean(TenantSalesOrderFulfillmentQueryFactory.class, () -> orders);
    }
}
