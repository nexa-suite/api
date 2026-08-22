package com.nexa.api.payments.application.port;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.UUID;

/** Outbound payment use-case persistence/provider boundary. */
public interface PaymentPersistencePort {
    PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context, int page, int size);
    PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(CurrentAccessContext context, int page, int size, String method, String status);
    PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(CurrentAccessContext context, UUID receivableId, int page, int size);
    PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(CurrentAccessContext context, int page, int size, String state);
    PaymentModels.ReconciliationCaseView retryReconciliationCase(CurrentAccessContext context, UUID caseId, String operatorNote, String idempotencyKey);
    PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId);
    PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId);
    PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, ReceivableCommand request);
    PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId, String idempotencyKey);
    PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context, UUID receivableId, String clientSecret);
    PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId, String idempotencyKey);
    PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId, String idempotencyKey,
                                                 String transferReference, UUID proofEvidenceId);
    PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId, String action,
                                                 String reason, String idempotencyKey);
    PaymentModels.WebhookReceipt receiveStripeWebhook(String payload, String signature);
    void processStripeWebhookInbox();

    record ReceivableCommand(String subjectType, UUID subjectId, Instant dueAt, String idempotencyKey) { }
}
