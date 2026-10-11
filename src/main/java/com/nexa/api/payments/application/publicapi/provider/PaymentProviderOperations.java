package com.nexa.api.payments.application.publicapi.provider;

import java.util.UUID;

/** Narrow trusted runtime capability for creating intents and reconciling provider refunds. */
public interface PaymentProviderOperations {
    boolean supportsPaymentIntentCreation();

    PaymentIntent createPaymentIntent(PaymentIntentRequest request);

    Refund refundPayment(String providerId, long amountMinor, String currency, String idempotencyKey);

    record PaymentIntentRequest(long amountMinor, String currency, String idempotencyKey,
            UUID paymentRouteId, UUID walletRechargeId, UUID tenantId, UUID workspaceId) { }

    record PaymentIntent(String providerId, String clientSecret, String status) { }

    record Refund(String providerRefundId, String status) { }
}
