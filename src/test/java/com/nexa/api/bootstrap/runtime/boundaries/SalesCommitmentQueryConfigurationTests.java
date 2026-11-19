package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork;
import com.nexa.api.salescommitment.application.model.SalesCommitmentQueryRunner;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory.Composition;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SalesCommitmentQueryConfigurationTests {
    private final PurchaseRequestUseCase existingRequests = mock(PurchaseRequestUseCase.class);
    private final SalesOrderUseCase existingOrders = mock(SalesOrderUseCase.class);
    private final TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
    private final TenantSalesCommitmentCompositionProvider provider = mock(TenantSalesCommitmentCompositionProvider.class);
    private final CurrentAccessContext buyer = mock(CurrentAccessContext.class);

    @Test
    void buyerUsesReadOnlyTenantCompositionAndRequiresBuyerPermissions() {
        when(buyer.surface()).thenReturn(Surface.PORTAL);
        when(buyer.hasRole(MembershipRole.BUYER)).thenReturn(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Composition composition = mock(Composition.class);
        PurchaseRequestUseCase tenantRequests = mock(PurchaseRequestUseCase.class);
        SalesOrderUseCase tenantOrders = mock(SalesOrderUseCase.class);
        when(composition.purchaseRequests()).thenReturn(tenantRequests);
        when(composition.salesOrders()).thenReturn(tenantOrders);
        when(provider.bindTo(jdbc)).thenReturn(composition);
        when(router.inReadOnlyTransaction(same(buyer), any()))
                .thenAnswer(invocation -> invocation.<TenantBusinessDatabaseWork<?>>getArgument(1).execute(jdbc));
        SalesCommitmentQueryRunner runner = runner(true);

        assertThat(runner.purchaseRequests(buyer, Function.identity())).isSameAs(tenantRequests);
        assertThat(runner.salesOrders(buyer, Function.identity())).isSameAs(tenantOrders);
        verify(buyer).requirePermission(PermissionKey.BUYER_SALES_READ);
        verify(buyer).requirePermission(PermissionKey.BUYER_ORDER_READ);
        verifyNoInteractions(existingRequests, existingOrders);
    }

    @Test
    void disabledAndInternalQueriesKeepExistingRouting() {
        CurrentAccessContext staff = mock(CurrentAccessContext.class);
        when(staff.surface()).thenReturn(Surface.PLATFORM);

        assertThat(runner(false).purchaseRequests(buyer, Function.identity())).isSameAs(existingRequests);
        assertThat(runner(true).purchaseRequests(staff, Function.identity())).isSameAs(existingRequests);
        assertThat(runner(true).salesOrders(staff, Function.identity())).isSameAs(existingOrders);
        verifyNoInteractions(router, provider);
    }

    @Test
    void unavailableTenantDoesNotFallBackToCentral() {
        when(buyer.surface()).thenReturn(Surface.PORTAL);
        when(buyer.hasRole(MembershipRole.BUYER)).thenReturn(true);
        when(router.inReadOnlyTransaction(same(buyer), any()))
                .thenThrow(new CannotGetJdbcConnectionException("Test database unavailable"));

        assertThatThrownBy(() -> runner(true).salesOrders(buyer, Function.identity()))
                .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
        verifyNoInteractions(existingOrders, provider);
    }

    @Test
    void salesOperationsOptInPreservesAccountLocksWithoutEnablingBuyerReads() {
        CurrentAccessContext sales = mock(CurrentAccessContext.class);
        when(sales.surface()).thenReturn(Surface.PLATFORM);
        when(sales.hasRole(MembershipRole.SALES)).thenReturn(true);
        when(buyer.surface()).thenReturn(Surface.PORTAL);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Composition tenant = mock(Composition.class);
        PurchaseRequestUseCase tenantRequests = mock(PurchaseRequestUseCase.class);
        SalesOrderUseCase tenantOrders = mock(SalesOrderUseCase.class);
        when(tenant.purchaseRequests()).thenReturn(tenantRequests);
        when(tenant.salesOrders()).thenReturn(tenantOrders);
        when(provider.bindTo(jdbc)).thenReturn(tenant);
        when(router.inReadOnlyTransaction(same(sales), any()))
                .thenAnswer(invocation -> invocation.<TenantBusinessDatabaseWork<?>>getArgument(1).execute(jdbc));
        when(router.inTransaction(same(sales), any()))
                .thenAnswer(invocation -> invocation.<TenantBusinessDatabaseWork<?>>getArgument(1).execute(jdbc));
        var runner = runner(false, true);
        assertThat(runner.purchaseRequests(sales, Function.identity())).isSameAs(tenantRequests);
        assertThat(runner.salesOrders(sales, Function.identity())).isSameAs(tenantOrders);
        assertThat(runner.purchaseRequests(buyer, Function.identity())).isSameAs(existingRequests);
        verify(sales).requirePermission(PermissionKey.SALES_PURCHASE_REQUEST_READ);
        verify(sales).requirePermission(PermissionKey.SALES_ORDER_READ);
    }

    private SalesCommitmentQueryRunner runner(boolean enabled) {
        return runner(enabled, false);
    }

    private SalesCommitmentQueryRunner runner(boolean buyerEnabled, boolean operationsEnabled) {
        var beans = new StaticListableBeanFactory();
        if (buyerEnabled || operationsEnabled) {
            beans.addBean("cloudSalesReads", new SalesCommitmentQueryConfiguration.CloudSalesReads(
                    router, provider, buyerEnabled, operationsEnabled));
        }
        return new SalesCommitmentQueryConfiguration().salesCommitmentQueries(existingRequests, existingOrders,
                beans.getBeanProvider(SalesCommitmentQueryConfiguration.CloudSalesReads.class));
    }
}
