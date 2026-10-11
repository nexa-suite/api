package com.nexa.api.salescommitment.application.salesorder.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.exception.BuyerWalletRefundUnavailableException;
import com.nexa.api.payments.application.publicapi.BuyerWalletRefundBalanceQuery;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.RefundCommand;
import com.nexa.api.salescommitment.application.exception.SalesOrderTransitionException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFinancialStateQuery;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderWalletRefundTests {
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();
    private static final UUID LEDGER_ENTRY_ID = UUID.randomUUID();
    private static final UUID REFUND_ID = UUID.randomUUID();

    @Test
    void refundsOnlyCancelledWalletOrdersAndNormalizesTheIdempotencyKey() {
        var ports = ports();
        CurrentAccessContext context = authorizedContext(MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        when(ports.financialStates.findWalletRefundState(TENANT_ID, WORKSPACE_ID, ORDER_ID))
                .thenReturn(Optional.of(new SalesOrderFinancialStateQuery.State("CANCELLED", "WALLET")));
        when(ports.wallet.refundForSalesOrder(eq(context), any(RefundCommand.class)))
                .thenAnswer(invocation -> {
                    RefundCommand command = invocation.getArgument(1);
                    return new BuyerWalletReservationCommands.Refund(LEDGER_ENTRY_ID, command.refundId(),
                            command.amountPEN());
                });

        var response = ports.service.refundBuyerWallet(context, ORDER_ID.toString(), new BigDecimal("12.5000"),
                " damaged package ", "  refund-key-1  ");

        assertThat(response.salesOrderId()).isEqualTo(ORDER_ID);
        assertThat(response.ledgerEntryId()).isEqualTo(LEDGER_ENTRY_ID);
        assertThat(response.currency()).isEqualTo("PEN");
        assertThat(response.status()).isEqualTo("POSTED");
        org.mockito.ArgumentCaptor<RefundCommand> command = org.mockito.ArgumentCaptor.forClass(RefundCommand.class);
        verify(ports.wallet).refundForSalesOrder(eq(context), command.capture());
        assertThat(command.getValue().reason()).isEqualTo("damaged package");
        assertThat(command.getValue().idempotencyKey()).isEqualTo("refund-key-1");
        assertThat(command.getValue().refundId()).isEqualTo(response.refundId());
        verify(context).requirePermission(PermissionKey.PAYMENT_RECONCILE);
    }

    @Test
    void rejectsNonCancelledOrdersBeforeCallingPayments() {
        var ports = ports();
        CurrentAccessContext context = authorizedContext(MembershipRole.COMPANY_OWNER);
        when(ports.financialStates.findWalletRefundState(TENANT_ID, WORKSPACE_ID, ORDER_ID))
                .thenReturn(Optional.of(new SalesOrderFinancialStateQuery.State("CONFIRMED", "WALLET")));

        assertThatThrownBy(() -> ports.service.refundBuyerWallet(context, ORDER_ID.toString(),
                BigDecimal.ONE, "operator reason", "refund-key-2"))
                .isInstanceOf(SalesOrderTransitionException.class);

        verify(ports.wallet, never()).refundForSalesOrder(any(), any());
    }

    @Test
    void companyOwnerCanRefundAndUsesExistingReconciliationPermission() {
        var ports = ports();
        CurrentAccessContext context = authorizedContext(MembershipRole.COMPANY_OWNER);
        when(ports.financialStates.findWalletRefundState(TENANT_ID, WORKSPACE_ID, ORDER_ID))
                .thenReturn(Optional.of(new SalesOrderFinancialStateQuery.State("CANCELLED", "WALLET")));
        when(ports.wallet.refundForSalesOrder(eq(context), any(RefundCommand.class)))
                .thenAnswer(invocation -> {
                    RefundCommand command = invocation.getArgument(1);
                    return new BuyerWalletReservationCommands.Refund(LEDGER_ENTRY_ID, command.refundId(),
                            command.amountPEN());
                });

        var response = ports.service.refundBuyerWallet(context, ORDER_ID.toString(), BigDecimal.ONE,
                "owner approved return", "owner-refund-key");

        assertThat(response.status()).isEqualTo("POSTED");
        verify(context).requirePermission(PermissionKey.PAYMENT_RECONCILE);
        verify(ports.wallet).refundForSalesOrder(eq(context), any(RefundCommand.class));
    }

    @Test
    void rejectsSalesWarehouseBuyerAndSystemWorkflowEvenWhenPermissionIsPresent() {
        for (CurrentAccessContext context : List.of(
                authorizedContext(MembershipRole.SALES, Surface.PLATFORM, true),
                authorizedContext(MembershipRole.WAREHOUSE, Surface.PLATFORM, true),
                authorizedContext(MembershipRole.BUYER, Surface.PORTAL, true),
                authorizedContext(null, Surface.PLATFORM, true))) {
            var ports = ports();
            assertThat(context.allows(PermissionKey.PAYMENT_RECONCILE)).isTrue();

            assertThatThrownBy(() -> ports.service.walletRefundCandidates(context, 0, 25))
                    .isInstanceOf(AccessPolicyViolation.class);
            assertThatThrownBy(() -> ports.service.refundBuyerWallet(context, ORDER_ID.toString(),
                    BigDecimal.ONE, "operator reason", "refund-denied-key"))
                    .isInstanceOf(AccessPolicyViolation.class);

            verify(ports.financialStates, never()).findCancelledWalletRefundCandidates(any(), any(), anyInt(), anyInt());
            verify(ports.financialStates, never()).findWalletRefundState(any(), any(), any());
            verify(ports.wallet, never()).refundForSalesOrder(any(), any());
        }
    }

    @Test
    void rejectsSystemWorkflowEvenThoughItHasTheReconciliationCapability() {
        var ports = ports();
        CurrentAccessContext context = authorizedContext(null);

        assertThatThrownBy(() -> ports.service.walletRefundCandidates(context, 0, 25))
                .isInstanceOf(AccessPolicyViolation.class);

        verify(ports.financialStates, never()).findCancelledWalletRefundCandidates(any(), any(), anyInt(), anyInt());
    }

    @Test
    void returnsSalesCandidatesEnrichedOnlyWithPaymentsRefundBalances() {
        var ports = ports();
        CurrentAccessContext context = authorizedContext(MembershipRole.COMPANY_OWNER);
        when(ports.financialStates.findCancelledWalletRefundCandidates(TENANT_ID, WORKSPACE_ID, 1, 10))
                .thenReturn(new SalesOrderFinancialStateQuery.CandidatePage(
                        List.of(new SalesOrderFinancialStateQuery.Candidate(ORDER_ID, "SO-2026-000001")), 1, 10, 23));
        when(ports.refundBalances.findRefundableBalances(eq(context), anyCollection()))
                .thenReturn(Map.of(ORDER_ID, new BuyerWalletRefundBalanceQuery.RefundableBalance(
                        new BigDecimal("40.0000"), new BigDecimal("7.5000"), new BigDecimal("32.5000"))));

        var page = ports.service.walletRefundCandidates(context, 1, 10);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(23);
        assertThat(page.items()).singleElement().satisfies(candidate -> {
            assertThat(candidate.salesOrderId()).isEqualTo(ORDER_ID);
            assertThat(candidate.orderNumber()).isEqualTo("SO-2026-000001");
            assertThat(candidate.currency()).isEqualTo("PEN");
            assertThat(candidate.consumedPEN()).isEqualByComparingTo("40.0000");
            assertThat(candidate.refundedPEN()).isEqualByComparingTo("7.5000");
            assertThat(candidate.refundablePEN()).isEqualByComparingTo("32.5000");
        });
        verify(ports.refundBalances).findRefundableBalances(eq(context), anyCollection());
    }

    @Test
    void failsClosedWhenTenantRefundPortsAreNotBound() {
        var ports = ports(false);
        CurrentAccessContext context = authorizedContext(MembershipRole.BUSINESS_OPERATIONS_MANAGER);

        assertThatThrownBy(() -> ports.service.walletRefundCandidates(context, 0, 25))
                .isInstanceOf(BuyerWalletRefundUnavailableException.class);
    }

    private static Ports ports() {
        return ports(true);
    }

    private static Ports ports(boolean withRefundPorts) {
        SalesOrderPersistencePort persistence = mock(SalesOrderPersistencePort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        SalesOrderAggregatePersistencePort aggregate = mock(SalesOrderAggregatePersistencePort.class);
        SalesOrderConversionPersistencePort conversion = mock(SalesOrderConversionPersistencePort.class);
        IdempotencyPersistencePort idempotency = mock(IdempotencyPersistencePort.class);
        SalesOrderFinancialStateQuery financialStates = mock(SalesOrderFinancialStateQuery.class);
        BuyerWalletReservationCommands wallet = mock(BuyerWalletReservationCommands.class);
        BuyerWalletRefundBalanceQuery refundBalances = mock(BuyerWalletRefundBalanceQuery.class);
        SalesOrderService service = new SalesOrderService(persistence, accounts, aggregate, conversion, idempotency,
                new ObjectMapper(), withRefundPorts ? financialStates : null,
                withRefundPorts ? wallet : null, withRefundPorts ? refundBalances : null);
        return new Ports(service, financialStates, wallet, refundBalances);
    }

    private static CurrentAccessContext authorizedContext(MembershipRole role) {
        return authorizedContext(role, Surface.PLATFORM,
                role == MembershipRole.BUSINESS_OPERATIONS_MANAGER || role == MembershipRole.COMPANY_OWNER
                        || role == null);
    }

    private static CurrentAccessContext authorizedContext(MembershipRole role, Surface surface,
            boolean paymentReconcile) {
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        when(context.surface()).thenReturn(surface);
        when(context.tenantId()).thenReturn(new TenantId(TENANT_ID));
        when(context.workspaceId()).thenReturn(new WorkspaceId(WORKSPACE_ID));
        when(context.membershipId()).thenReturn(new MembershipId(MEMBERSHIP_ID));
        when(context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER))
                .thenReturn(role == MembershipRole.BUSINESS_OPERATIONS_MANAGER);
        when(context.hasRole(MembershipRole.COMPANY_OWNER)).thenReturn(role == MembershipRole.COMPANY_OWNER);
        when(context.hasRoleCode("system_workflow")).thenReturn(role == null);
        when(context.permissionCodes()).thenReturn(paymentReconcile
                ? Set.of(PermissionKey.PAYMENT_RECONCILE.code()) : Set.of());
        when(context.allows(PermissionKey.PAYMENT_RECONCILE)).thenReturn(paymentReconcile);
        return context;
    }

    private record Ports(SalesOrderService service, SalesOrderFinancialStateQuery financialStates,
            BuyerWalletReservationCommands wallet, BuyerWalletRefundBalanceQuery refundBalances) { }
}
