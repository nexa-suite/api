package com.nexa.api.payments.application.publicapi.webhooks;

/** Verifies a provider callback and returns only the routing and status facts required by trusted ingress. */
public interface PaymentWebhookSignatureVerifier {
    VerifiedEvent verify(String payload, String signature);

    record VerifiedEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
            Long amountMinor, String currency, String paymentRouteReference, String walletRechargeReference) { }
}
