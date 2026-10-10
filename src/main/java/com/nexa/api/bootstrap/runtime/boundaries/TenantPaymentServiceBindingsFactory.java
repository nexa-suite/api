package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.BusinessDocumentCommands;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentServiceFactory;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.payments.application.publicapi.PaymentSalesSource;
import com.nexa.api.payments.infrastructure.persistence.PaymentService;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentServiceFactory;
import com.nexa.api.payments.infrastructure.persistence.TenantPaymentSession;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;
import java.util.UUID;

/** Binds Payments plus BC-02/04/07/09 collaborators to one router-owned Tenant JDBC session. */
@Component
@Profile("local")
public final class TenantPaymentServiceBindingsFactory implements TenantPaymentServiceFactory {
    private final StripePaymentProvider stripe;
    private final String publishableKey;
    private final String webhookSecret;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantCreditAccountAdapterFactory creditAdapters;
    private final TenantSalesOrderFulfillmentQueryFactory salesOrders;
    private final TenantPaymentConfirmationQueryFactory paymentConfirmations;
    private final TenantBusinessTraceabilityBindingsFactory traceability;
    private final TenantBusinessDocumentServiceFactory documents;
    private final TenantBusinessEvidenceQueryFactory evidence;

    public TenantPaymentServiceBindingsFactory(StripePaymentProvider stripe,
            @Value("${nexa.payments.publishable-key:}") String publishableKey,
            @Value("${nexa.payments.webhook-secret:}") String webhookSecret,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantPaymentConfirmationQueryFactory paymentConfirmations,
            TenantBusinessTraceabilityBindingsFactory traceability,
            TenantBusinessDocumentServiceFactory documents,
            TenantBusinessEvidenceQueryFactory evidence) {
        this.stripe = Objects.requireNonNull(stripe);
        this.publishableKey = publishableKey == null ? "" : publishableKey;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.creditAdapters = Objects.requireNonNull(creditAdapters);
        this.salesOrders = Objects.requireNonNull(salesOrders);
        this.paymentConfirmations = Objects.requireNonNull(paymentConfirmations);
        this.traceability = Objects.requireNonNull(traceability);
        this.documents = Objects.requireNonNull(documents);
        this.evidence = Objects.requireNonNull(evidence);
    }

    @Override
    public TenantPaymentSession bindTo(JdbcTemplate tenantJdbc, PlatformTransactionManager tenantTransactionManager) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        PlatformTransactionManager transactionManager = Objects.requireNonNull(tenantTransactionManager,
                "Tenant transaction manager is required");
        CustomerAccountQuery tenantAccounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc));
        ReceivablePaymentAccess tenantReceivables = Objects.requireNonNull(
                creditAdapters.bindReceivablePaymentAccessTo(jdbc));
        SalesOrderFulfillmentQuery tenantSales = Objects.requireNonNull(salesOrders.bindTo(jdbc));
        PaymentConfirmationQuery tenantPayments = Objects.requireNonNull(
                paymentConfirmations.bindTo(jdbc, tenantReceivables));
        TenantBusinessTraceabilityBindingsFactory.Bindings trace = traceability.bindTo(jdbc);
        CanonicalOutboxPort tenantOutbox = trace.canonicalOutbox();
        BusinessTraceabilityCommands tenantTraceability = trace.commands();
        FinancialAdjustmentSource adjustmentSource = adjustmentSource(tenantSales, tenantPayments);
        TenantCreditAccountAdapterFactory.Bindings credit = Objects.requireNonNull(creditAdapters.bindTo(
                jdbc, tenantAccounts, tenantReceivables, tenantTraceability, tenantOutbox, adjustmentSource));
        BusinessDocumentCommands tenantDocuments = Objects.requireNonNull(
                documents.bindCommandsTo(jdbc, tenantOutbox));
        BusinessEvidenceQuery tenantEvidence = Objects.requireNonNull(evidence.bindTo(jdbc));
        return new PaymentService(jdbc, stripe, publishableKey, webhookSecret,
                transactionManager, null,
                credit.receivableApplications(), credit.creditPayments(), credit.receivables(), tenantDocuments,
                tenantEvidence, tenantAccounts, paymentSalesSource(tenantSales), tenantOutbox,
                tenantReceivables);
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

    private static PaymentSalesSource paymentSalesSource(SalesOrderFulfillmentQuery sales) {
        return new PaymentSalesSource() {
            @Override
            public Snapshot claimPayableSubject(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                SalesOrderFulfillmentQuery.Snapshot order = sales.getForUpdate(tenantId, workspaceId, salesOrderId);
                return new Snapshot(order.clientAccountId(), order.total(), order.currency(), order.status(),
                        order.paymentOption());
            }

            @Override
            public boolean exists(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                try {
                    sales.get(tenantId, workspaceId, salesOrderId);
                    return true;
                } catch (CommercialBusinessException exception) {
                    if (!"SALES_ORDER_NOT_FOUND".equals(exception.code())) throw exception;
                    return false;
                }
            }
        };
    }
}
