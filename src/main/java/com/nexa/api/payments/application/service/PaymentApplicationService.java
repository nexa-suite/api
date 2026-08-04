package com.nexa.api.payments.application.service;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.application.port.PaymentPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Application use-case boundary. Financial persistence stays behind the outbound port. */
@Profile("!test")
@Service
public class PaymentApplicationService implements PaymentPort {
    private final PaymentPersistencePort persistence;

    public PaymentApplicationService(PaymentPersistencePort persistence) {
        this.persistence = persistence;
    }

    @Override
    public PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context, int page, int size) {
        return persistence.listReceivables(context, page, size);
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
    public PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey) {
        return persistence.createCardPaymentIntent(context, receivableId, idempotencyKey);
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

    @Override
    public PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature) {
        return persistence.receiveStripeWebhook(payload, signature);
    }

    @Override
    public void processStripeWebhookInbox() {
        persistence.processStripeWebhookInbox();
    }
}
