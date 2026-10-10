package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.model.PaymentModels;
import com.nexa.api.payments.application.port.PaymentPersistencePort;
import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** One Payment service instance bound to a single router-owned Tenant JDBC session. */
public interface TenantPaymentSession extends PaymentPersistencePort {
    TenantCardPaymentPreparation prepareTenantCardPayment(CurrentAccessContext context, UUID receivableId,
            String idempotencyKey);

    PaymentModels.PaymentIntentView persistTenantCardPayment(CurrentAccessContext context,
            TenantCardPaymentPreparation preparation, StripePaymentProvider.PaymentIntent intent);

    void recordTenantCardPaymentFailure(CurrentAccessContext context, TenantCardPaymentPreparation preparation,
            RuntimeException failure);

    TenantTestCardPreparation prepareTenantTestCardConfirmation(CurrentAccessContext context, UUID receivableId);

    TenantTestCardWebhook confirmTenantTestCardPayment(CurrentAccessContext context, UUID receivableId,
            String clientSecret, UUID opaqueRouteId);

    TenantPaymentProviderEventResult applyTenantPaymentProviderEvent(UUID tenantId, UUID workspaceId,
            UUID expectedPaymentId, String eventId, String eventType, String providerPaymentIntentId,
            String providerStatus, Long amountMinor, String currency);

    TenantReconciliationRefundPreparation claimNextTenantReconciliationRefund(CurrentAccessContext workflowContext);

    TenantReconciliationRefundOutcome recordTenantReconciliationRefund(CurrentAccessContext workflowContext,
            TenantReconciliationRefundPreparation preparation, StripePaymentProvider.Refund refund,
            RuntimeException providerFailure);

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
