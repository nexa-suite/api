package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.util.UUID;

/** Application operations consumed by Payments presentation and trusted runtime composition. */
public interface PaymentUseCases {
    PaymentModels.Page<PaymentModels.ReceivableView> listReceivables(CurrentAccessContext context, int page, int size);

    PaymentModels.Page<PaymentModels.PaymentSummaryView> listPayments(CurrentAccessContext context, int page, int size,
            String method, String status);

    PaymentModels.Page<PaymentModels.PaymentSummaryView> listPaymentsForReceivable(CurrentAccessContext context,
            UUID receivableId, int page, int size);

    PaymentModels.Page<PaymentModels.ReconciliationCaseView> listReconciliationCases(CurrentAccessContext context,
            int page, int size, String state);

    PaymentModels.ReconciliationCaseView retryReconciliationCase(CurrentAccessContext context, UUID caseId,
            String operatorNote, String idempotencyKey);

    PaymentModels.ReceivableView getReceivable(CurrentAccessContext context, UUID receivableId);

    PaymentModels.PaymentView getPayment(CurrentAccessContext context, UUID paymentId);

    PaymentModels.ReceivableView createReceivable(CurrentAccessContext context, CreateReceivableCommand request);

    PaymentModels.PaymentIntentView createCardPaymentIntent(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey);

    PaymentModels.PaymentView confirmTestCardPayment(CurrentAccessContext context, UUID receivableId,
            String clientSecret);

    PaymentModels.PaymentView createCreditLinePayment(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey);

    PaymentModels.PaymentView createBankTransfer(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey, String transferReference, UUID proofEvidenceId);

    PaymentModels.PaymentView reviewBankTransfer(CurrentAccessContext context, UUID paymentId,
            String action, String reason, String idempotencyKey);

    java.util.List<PaymentModels.BuyerSavedPaymentMethodView> listBuyerSavedPaymentMethods(
            CurrentAccessContext context);

    PaymentModels.BuyerPaymentMethodSetupView createBuyerPaymentMethodSetup(CurrentAccessContext context,
            String idempotencyKey);

    PaymentModels.BuyerSavedPaymentMethodView setBuyerSavedPaymentMethodDefault(CurrentAccessContext context,
            UUID paymentMethodId);

    void removeBuyerSavedPaymentMethod(CurrentAccessContext context, UUID paymentMethodId);

    /** Command value used by trusted callers; persistence-specific commands remain internal to BC-08. */
    record CreateReceivableCommand(String subjectType, UUID subjectId, Instant dueAt, String idempotencyKey) { }
}
