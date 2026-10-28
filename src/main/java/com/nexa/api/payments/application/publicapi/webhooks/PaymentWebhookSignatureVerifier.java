package com.nexa.api.payments.application.publicapi.webhooks;

/** Verifies a provider callback and returns only the routing and status facts required by trusted ingress. */
public interface PaymentWebhookSignatureVerifier {
    VerifiedEvent verify(String payload, String signature);

    record VerifiedEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
            Long amountMinor, String currency, String paymentRouteReference, String walletRechargeReference,
            String setupIntentId, String providerCustomerId, String providerPaymentMethodId,
            java.util.Map<String, String> metadata) {
        public VerifiedEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                Long amountMinor, String currency, String paymentRouteReference, String walletRechargeReference) {
            this(eventId, eventType, paymentIntentId, paymentStatus, amountMinor, currency, paymentRouteReference,
                    walletRechargeReference, null, null, null, java.util.Map.of());
        }

        public VerifiedEvent {
            metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        }
    }
}
