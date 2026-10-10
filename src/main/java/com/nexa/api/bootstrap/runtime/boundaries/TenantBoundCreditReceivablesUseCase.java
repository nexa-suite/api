package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.creditreceivables.application.exception.CreditReceivablesUnavailableException;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureUseCase;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentUseCase;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.application.service.CreditExposureApplicationService;
import com.nexa.api.creditreceivables.application.service.FinancialAdjustmentApplicationService;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Runs BC-07 HTTP use cases on one verified Tenant transaction and reuses application rules. */
public final class TenantBoundCreditReceivablesUseCase
        implements CreditExposureUseCase, FinancialAdjustmentUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantCreditAccountAdapterFactory creditAdapters;
    private final TenantSalesOrderFulfillmentQueryFactory salesOrders;
    private final TenantPaymentConfirmationQueryFactory paymentConfirmations;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;
    private final Clock clock;

    public TenantBoundCreditReceivablesUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantPaymentConfirmationQueryFactory paymentConfirmations,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings, Clock clock) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "Tenant Customer Account query factory is required");
        this.creditAdapters = Objects.requireNonNull(creditAdapters,
                "Tenant Credit & Receivables adapter factory is required");
        this.salesOrders = Objects.requireNonNull(salesOrders, "Tenant Sales order query factory is required");
        this.paymentConfirmations = Objects.requireNonNull(paymentConfirmations,
                "Tenant payment confirmation query factory is required");
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings,
                "Tenant traceability binding factory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public CreditExposureUseCase.CreditExposureView read(CurrentAccessContext context, String clientAccountId,
            String currency) {
        requireScope(context);
        context.requirePermission(PermissionKey.CLIENT_READ);
        return inTenant(context, bindings -> new CreditExposureApplicationService(
                bindings.customerAccounts(), bindings.creditExposure()).read(context, clientAccountId, currency));
    }

    @Override
    public CreditExposureUseCase.CreditExposureView readBuyer(CurrentAccessContext context, String currency) {
        requireScope(context);
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(PermissionKey.PAYMENT_READ);
        return inTenant(context, bindings -> new CreditExposureApplicationService(
                bindings.customerAccounts(), bindings.creditExposure()).readBuyer(context, currency));
    }

    @Override
    public FinancialAdjustmentCommands.Result postPostPayment(CurrentAccessContext context, UUID receivableId,
            long expectedReceivableVersion, String idempotencyKey,
            FinancialAdjustmentUseCase.PostPaymentCommand command) {
        requireScope(context);
        context.requirePermission(PermissionKey.CLIENT_CREDIT_MANAGE);
        return inTenant(context, bindings -> new FinancialAdjustmentApplicationService(
                bindings.financialAdjustments(), clock).postPostPayment(context, receivableId,
                expectedReceivableVersion, idempotencyKey, command));
    }

    private <T> T inTenant(CurrentAccessContext context,
            Function<TenantCreditAccountAdapterFactory.Bindings, T> operation) {
        try {
            return router.inTransaction(context, jdbc -> operation.apply(bind(jdbc)));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            throw new CreditReceivablesUnavailableException(unavailable);
        }
    }

    private TenantCreditAccountAdapterFactory.Bindings bind(JdbcTemplate jdbc) {
        CustomerAccountQuery accounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account factory returned no query");
        ReceivablePaymentAccess receivables = Objects.requireNonNull(creditAdapters.bindReceivablePaymentAccessTo(jdbc),
                "Tenant Credit factory returned no receivable access");
        SalesOrderFulfillmentQuery sales = Objects.requireNonNull(salesOrders.bindTo(jdbc),
                "Tenant Sales factory returned no fulfillment query");
        PaymentConfirmationQuery payments = Objects.requireNonNull(paymentConfirmations.bindTo(jdbc, receivables),
                "Tenant Payment factory returned no confirmation query");
        TenantBusinessTraceabilityBindingsFactory.Bindings traceability = traceabilityBindings.bindTo(jdbc);
        return Objects.requireNonNull(creditAdapters.bindTo(jdbc, accounts, receivables,
                traceability.commands(), traceability.canonicalOutbox(), adjustmentSource(sales, payments)),
                "Tenant Credit factory returned no bindings");
    }

    private static FinancialAdjustmentSource adjustmentSource(SalesOrderFulfillmentQuery sales,
            PaymentConfirmationQuery payments) {
        return new FinancialAdjustmentSource() {
            @Override
            public Snapshot claimSalesOrderCorrection(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                SalesOrderFulfillmentQuery.Snapshot order = sales.getForUpdate(tenantId, workspaceId, salesOrderId);
                return new Snapshot(order.status(), order.currency(), order.total());
            }

            @Override
            public boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return payments.hasSuccessfulPayment(tenantId, workspaceId, salesOrderId);
            }
        };
    }

    private static void requireScope(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        RlsRequestScope.Scope current = RlsRequestScope.current();
        if (current == null || !tenantId.equals(current.tenantId()) || !workspaceId.equals(current.workspaceId())) {
            throw new AccessPolicyViolation("Credit and receivables require the matching verified Tenant and Workspace scope");
        }
    }
}
