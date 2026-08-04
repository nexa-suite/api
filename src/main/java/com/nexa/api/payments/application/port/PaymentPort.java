package com.nexa.api.payments.application.port;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface PaymentPort {
    PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId);
    PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId);
    PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, ReceivableCommand request);
    PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey);
    PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId, String idempotencyKey);
    PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId, String idempotencyKey);
    PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature);
    void processStripeWebhookInbox();

    record ReceivableCommand(UUID clientAccountId, String subjectType, UUID subjectId, BigDecimal amount, String currency, Instant dueAt) { }
}
