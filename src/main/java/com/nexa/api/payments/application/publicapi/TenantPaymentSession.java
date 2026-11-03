package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.publicapi.model.PaymentModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** Short-lived Payments use cases bound to one router-owned Tenant JDBC session. */
public interface TenantPaymentSession {
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

    PaymentModels.ReceivableView createReceivable(CurrentAccessContext context,
            PaymentUseCases.CreateReceivableCommand request);

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

    BuyerPaymentMethodSetupPreparation prepareBuyerPaymentMethodSetup(CurrentAccessContext context,
            String idempotencyKey);

    BuyerPaymentMethodSetupPreparation prepareBuyerPaymentMethodLocalConfirmation(CurrentAccessContext context,
            String providerSetupIntentId);

    void bindBuyerPaymentProviderCustomer(CurrentAccessContext context,
            BuyerPaymentMethodSetupPreparation preparation, String providerCustomerId);

    void recordBuyerPaymentSetupIntent(CurrentAccessContext context,
            BuyerPaymentMethodSetupPreparation preparation, String providerCustomerId, String providerSetupIntentId);

    PaymentModels.WebhookReceipt applyBuyerPaymentMethodSetupWebhook(UUID tenantId, UUID workspaceId,
            UUID buyerMembershipId, UUID clientAccountId, UUID setupAttemptId, String setupIntentId,
            String providerCustomerId, String eventId, String eventType, String providerStatus,
            String signatureSha256,
            com.nexa.api.payments.application.publicapi.provider.PaymentProviderOperations.ProviderPaymentMethod method);

    BuyerPaymentMethodDetachPreparation prepareBuyerPaymentMethodDetach(CurrentAccessContext context,
            UUID paymentMethodId);

    void removeBuyerSavedPaymentMethod(CurrentAccessContext context, UUID paymentMethodId);

    PaymentModels.BuyerSavedPaymentMethodView setBuyerSavedPaymentMethodDefault(CurrentAccessContext context,
            UUID paymentMethodId);

    TenantCardPaymentPreparation prepareTenantCardPayment(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey);

    PaymentModels.PaymentIntentView persistTenantCardPayment(CurrentAccessContext context,
            TenantCardPaymentPreparation preparation, ProviderIntent intent);

    void recordTenantCardPaymentFailure(CurrentAccessContext context,
            TenantCardPaymentPreparation preparation, RuntimeException failure);

    TenantTestCardPreparation prepareTenantTestCardConfirmation(CurrentAccessContext context, UUID receivableId);

    TenantTestCardWebhook confirmTenantTestCardPayment(CurrentAccessContext context, UUID receivableId,
            String clientSecret, UUID opaqueRouteId);

    TenantPaymentProviderEventResult applyTenantPaymentProviderEvent(UUID tenantId, UUID workspaceId,
            UUID expectedPaymentId, String eventId, String eventType, String providerPaymentIntentId,
            String providerStatus, Long amountMinor, String currency);

    TenantReconciliationRefundPreparation claimNextTenantReconciliationRefund(CurrentAccessContext workflowContext);

    TenantReconciliationRefundOutcome recordTenantReconciliationRefund(CurrentAccessContext workflowContext,
            TenantReconciliationRefundPreparation preparation, ProviderRefund refund,
            RuntimeException providerFailure);

    record ProviderIntent(String providerId, String clientSecret, String status) { }

    record ProviderRefund(String providerRefundId, String status) { }

    record BuyerPaymentMethodSetupPreparation(UUID tenantId, UUID workspaceId, UUID clientAccountId,
            UUID buyerMembershipId, UUID setupAttemptId, String idempotencyKeyHash,
            String providerCustomerId, String providerSetupIntentId,
            String providerCustomerIdempotencyKey, String providerSetupIntentIdempotencyKey,
            java.util.Map<String, String> providerMetadata) { }

    record BuyerPaymentMethodDetachPreparation(UUID clientAccountId, UUID buyerMembershipId,
            UUID paymentMethodId, String providerCustomerId, String providerPaymentMethodId) { }

    record TenantCardPaymentPreparation(UUID paymentId, UUID receivableId, long amountMinor, String currency,
            String providerIdempotencyKey, String idempotencyKey, String providerPaymentIntentId,
            PaymentModels.PaymentIntentView replay) { }

    record TenantTestCardPreparation(UUID paymentId, UUID receivableId, String status,
            String providerPaymentIntentId, BigDecimal amount, String currency) { }

    record TenantTestCardWebhook(String payload, String signature) { }

    enum TenantPaymentProviderEventOutcome { PROCESSED, IGNORED }

    record TenantPaymentProviderEventResult(TenantPaymentProviderEventOutcome outcome, String paymentStatus) { }

    record TenantReconciliationRefundPreparation(UUID caseId, UUID tenantId, UUID workspaceId, UUID paymentId,
            String providerId, BigDecimal amount, long amountMinor, String currency, UUID claimToken,
            String providerIdempotencyKey) { }

    enum TenantReconciliationRefundOutcome { SUCCEEDED, PENDING, FAILED, CLAIM_LOST }
}
