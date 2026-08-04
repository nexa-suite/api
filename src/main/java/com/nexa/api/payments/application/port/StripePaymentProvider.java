package com.nexa.api.payments.application.port;

import java.util.Map;

public interface StripePaymentProvider {
    PaymentIntent createPaymentIntent(PaymentIntentRequest request);
    StripeWebhookEvent verifyWebhook(String payload, String signature);

    record PaymentIntentRequest(long amountMinor, String currency, String idempotencyKey, Map<String, String> metadata) { }
    record PaymentIntent(String providerId, String clientSecret, String status) { }
    record StripeWebhookEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                              Long amountMinor, String currency) { }
}
