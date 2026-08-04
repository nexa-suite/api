package com.nexa.api.payments.application.port;

import java.util.Map;
import java.util.Optional;

public interface StripePaymentProvider {
    PaymentIntent createPaymentIntent(PaymentIntentRequest request);
    default Optional<PaymentIntent> retrievePaymentIntent(String providerId) { return Optional.empty(); }
    StripeWebhookEvent verifyWebhook(String payload, String signature);

    record PaymentIntentRequest(long amountMinor, String currency, String idempotencyKey, Map<String, String> metadata) { }
    record PaymentIntent(String providerId, String clientSecret, String status) { }
    record StripeWebhookEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                              Long amountMinor, String currency, Map<String, String> metadata) {
        public StripeWebhookEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                                  Long amountMinor, String currency) {
            this(eventId, eventType, paymentIntentId, paymentStatus, amountMinor, currency, Map.of());
        }

        public StripeWebhookEvent {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
