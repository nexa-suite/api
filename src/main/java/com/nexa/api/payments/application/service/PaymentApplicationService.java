package com.nexa.api.payments.application.service;

import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.application.port.PaymentPort;
import com.nexa.api.payments.application.publicapi.PaymentUseCases;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.List;

/** Application use-case boundary. Financial persistence stays behind the outbound port. */
@Profile("!test")
@Service
public class PaymentApplicationService implements PaymentPort, PaymentUseCases {
    private final PaymentPersistencePort persistence;

    public PaymentApplicationService(PaymentPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Override
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context, int page, int size) {
        return persistence.listReceivables(context, page, size);
    }

    @Override
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(CurrentAccessContext context, int page, int size, String method, String status) {
        return persistence.listPayments(context, page, size, method, status);
    }

    @Override
    public PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(CurrentAccessContext context, UUID receivableId, int page, int size) {
        return persistence.listPaymentsForReceivable(context, receivableId, page, size);
    }

    @Override
    public PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(CurrentAccessContext context, int page, int size, String state) {
        return persistence.listReconciliationCases(context, page, size, state);
    }

    @Override
    public PaymentModels.ReconciliationCaseView retryReconciliationCase(CurrentAccessContext context, UUID caseId, String operatorNote, String idempotencyKey) {
        return persistence.retryReconciliationCase(context, caseId, operatorNote, idempotencyKey);
    }

    @Override
    public PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId) {
        return persistence.getReceivable(context, receivableId);
    }

    @Override
    public PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId) {
        return persistence.getPayment(context, paymentId);
    }

    @Override
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, PaymentPersistencePort.ReceivableCommand request) {
        return persistence.createReceivable(context, request);
    }

    @Override
    public PaymentModels.ReceivableView createReceivable(CurrentAccessContext context,
            PaymentUseCases.CreateReceivableCommand request) {
        return persistence.createReceivable(context, new PaymentPersistencePort.ReceivableCommand(
                request.subjectType(), request.subjectId(), request.dueAt(), request.idempotencyKey()));
    }

    @Override
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        return persistence.createCardPaymentIntent(context, receivableId, idempotencyKey);
    }

    @Override
    public PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context, UUID receivableId, String clientSecret) {
        return persistence.confirmTestCardPayment(context, receivableId, clientSecret);
    }

    @Override
    public PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        return persistence.createCreditLinePayment(context, receivableId, idempotencyKey);
    }

    @Override
    public PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId, String idempotencyKey,
                                                        String transferReference, UUID proofEvidenceId) {
        return persistence.createBankTransfer(context, receivableId, idempotencyKey, transferReference, proofEvidenceId);
    }

    @Override
    public PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId, String action,
                                                        String reason, String idempotencyKey) {
        return persistence.reviewBankTransfer(context, paymentId, action, reason, idempotencyKey);
    }

    @Override public List<PaymentModels.BuyerSavedPaymentMethodView> listBuyerSavedPaymentMethods(CurrentAccessContext context) { return persistence.listBuyerSavedPaymentMethods(context); }
    @Override public PaymentModels.BuyerPaymentMethodSetupView createBuyerPaymentMethodSetup(CurrentAccessContext context, String idempotencyKey) { return persistence.createBuyerPaymentMethodSetup(context, idempotencyKey); }
    @Override public PaymentModels.BuyerSavedPaymentMethodView setBuyerSavedPaymentMethodDefault(CurrentAccessContext context, UUID paymentMethodId) { return persistence.setBuyerSavedPaymentMethodDefault(context, paymentMethodId); }
    @Override public void removeBuyerSavedPaymentMethod(CurrentAccessContext context, UUID paymentMethodId) { persistence.removeBuyerSavedPaymentMethod(context, paymentMethodId); }

    @Override
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        return persistence.receiveStripeWebhook(payload, signature);
    }

    @Override
    public void processStripeWebhookInbox() {
        persistence.processStripeWebhookInbox();
    }
}
