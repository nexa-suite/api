package com.nexa.api.bootstrap.runtime.boundaries.payments;

import com.nexa.api.payments.application.publicapi.PaymentUseCases;
import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.payments.application.publicapi.webhooks.PaymentWebhookIntake;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.UUID;
import java.util.List;

/** Fails closed when HTTP payment requests have no verified Tenant persistence adapter. */
public final class TenantPaymentUnavailableAdapter implements PaymentUseCases, PaymentWebhookIntake {
    @Override
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context,
            int page, int size) {
        throw unavailable();
    }

    @Override
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(CurrentAccessContext context,
            int page, int size, String method, String status) {
        throw unavailable();
    }

    @Override
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(
            CurrentAccessContext context, UUID receivableId, int page, int size) {
        throw unavailable();
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
        throw unavailable();
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
        throw unavailable();
    }

    @Override
    public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId,
            String action, String reason, String idempotencyKey) {
        throw unavailable();
    }

    @Override public List<PaymentModels.BuyerSavedPaymentMethodView> listBuyerSavedPaymentMethods(CurrentAccessContext context) { throw unavailable(); }
    @Override public PaymentModels.BuyerPaymentMethodSetupView createBuyerPaymentMethodSetup(CurrentAccessContext context, String idempotencyKey) { throw unavailable(); }
    @Override public PaymentModels.BuyerSavedPaymentMethodView setBuyerSavedPaymentMethodDefault(CurrentAccessContext context, UUID paymentMethodId) { throw unavailable(); }
    @Override public void removeBuyerSavedPaymentMethod(CurrentAccessContext context, UUID paymentMethodId) { throw unavailable(); }

    @Override
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        throw unavailable();
    }

    private static TechnicalFailureException unavailable() {
        return new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                "Tenant payment database routing is unavailable");
    }
}
