package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.bootstrap.runtime.boundaries.TenantPaymentServiceBindingsFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.payments.application.publicapi.PaymentUseCases;
import com.nexa.api.payments.application.publicapi.TenantPaymentSession;
import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.payments.application.publicapi.webhooks.PaymentWebhookIntake;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Selects the Buyer receivable reads and transfer reporting supplied by the cloud profile. */
public final class TenantPaymentUnavailableAdapter implements PaymentUseCases, PaymentWebhookIntake {
    private final BuyerReceivableRoutes buyerReceivables;

    TenantPaymentUnavailableAdapter(BuyerReceivableRoutes buyerReceivables) {
        this.buyerReceivables = buyerReceivables;
    }

    @Override
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context,
            int page, int size) {
        return readBuyerReceivable(context, session -> session.listReceivables(context, page, size));
    }

    @Override
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(CurrentAccessContext context,
            int page, int size, String method, String status) {
        throw unavailable();
    }

    @Override
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(
            CurrentAccessContext context, UUID receivableId, int page, int size) {
        return readBuyerReceivable(context, session -> session.listPaymentsForReceivable(context, receivableId, page, size));
    }

    @Override
    public PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(
            CurrentAccessContext context, int page, int size, String state) {
        throw unavailable();
    }

    @Override
    public PaymentModels.ReconciliationCaseView retryReconciliationCase(CurrentAccessContext context,
            UUID caseId, String operatorNote, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId) {
        return readBuyerReceivable(context, session -> session.getReceivable(context, receivableId));
    }

    @Override
    public PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId) {
        throw unavailable();
    }

    @Override
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context,
            PaymentUseCases.CreateReceivableCommand request) {
        throw unavailable();
    }

    @Override
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context,
            UUID receivableId, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context,
            UUID receivableId, String clientSecret) {
        throw unavailable();
    }

    @Override
    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context,
            UUID receivableId, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey, String transferReference, UUID proofEvidenceId) {
        BuyerReceivableRoutes routes = requireBuyerReceivableRoutes(context);
        if (!routes.bankTransferReportingEnabled()) throw unavailable();
        return routes.write(context, session -> session.createBankTransfer(context, receivableId, idempotencyKey,
                transferReference, proofEvidenceId));
    }

    @Override
    public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId,
            String action, String reason, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public List<PaymentModels.BuyerSavedPaymentMethodView> listBuyerSavedPaymentMethods(CurrentAccessContext context) {
        return readBuyerReceivable(context, session -> session.listBuyerSavedPaymentMethods(context));
    }
    @Override public PaymentModels.BuyerPaymentMethodSetupView createBuyerPaymentMethodSetup(CurrentAccessContext context, String idempotencyKey) { throw unavailable(); }
    @Override public PaymentModels.BuyerSavedPaymentMethodView setBuyerSavedPaymentMethodDefault(CurrentAccessContext context, UUID paymentMethodId) { throw unavailable(); }
    @Override public void removeBuyerSavedPaymentMethod(CurrentAccessContext context, UUID paymentMethodId) { throw unavailable(); }

    @Override
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        throw unavailable();
    }

    private <T> T readBuyerReceivable(CurrentAccessContext context, Function<TenantPaymentSession, T> read) {
        return requireBuyerReceivableRoutes(context).read(context, read);
    }

    private BuyerReceivableRoutes requireBuyerReceivableRoutes(CurrentAccessContext context) {
        if (buyerReceivables == null || context == null || context.surface() != Surface.PORTAL
                || !context.hasRole(MembershipRole.BUYER)) {
            throw unavailable();
        }
        return buyerReceivables;
    }

    record BuyerReceivableRoutes(TenantBusinessDatabaseRouter router, TenantPaymentServiceBindingsFactory services,
                                boolean bankTransferReportingEnabled) {
        private <T> T read(CurrentAccessContext context, Function<TenantPaymentSession, T> query) {
            try {
                return router.inTenantSession(context, session -> session.inReadOnlyTransaction(
                        jdbc -> query.apply(services.bindTo(jdbc, session.transactionManager()))));
            } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                     | DataAccessException | CannotCreateTransactionException failure) {
                throw unavailable(failure);
            }
        }

        private <T> T write(CurrentAccessContext context, Function<TenantPaymentSession, T> command) {
            try {
                return router.inTenantSession(context, session ->
                        command.apply(services.bindTo(session.jdbcTemplate(), session.transactionManager())));
            } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                     | DataAccessException | CannotCreateTransactionException failure) {
                throw unavailable(failure);
            }
        }
    }

    private static TechnicalFailureException unavailable() {
        return unavailable(null);
    }

    private static TechnicalFailureException unavailable(Throwable cause) {
        return new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                "Tenant payment database routing is unavailable", cause);
    }
}
