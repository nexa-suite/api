package com.nexa.api.payments.application.publicapi;

import com.nexa.api.payments.application.model.PaymentModels.WebhookReceipt;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stores a Stripe event after the HTTP boundary verified its signature and opaque Tenant route. */
public interface TenantPaymentWebhookInboxIntake {
    WebhookReceipt acceptVerifiedEvent(VerifiedEvent event);

    record VerifiedEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                         Long amountMinor, String currency, String signatureSha256, Instant receivedAt,
                         UUID paymentRouteId) {
        public VerifiedEvent {
            Objects.requireNonNull(eventId, "Stripe event id is required");
            Objects.requireNonNull(eventType, "Stripe event type is required");
            Objects.requireNonNull(paymentIntentId, "Stripe payment intent id is required");
            Objects.requireNonNull(currency, "Stripe currency is required");
            Objects.requireNonNull(signatureSha256, "Stripe signature fingerprint is required");
            Objects.requireNonNull(receivedAt, "Stripe event receipt time is required");
            Objects.requireNonNull(paymentRouteId, "Payment route id is required");
            if (eventId.isBlank() || eventId.length() > 160 || eventType.isBlank() || eventType.length() > 160
                    || paymentIntentId.isBlank() || paymentIntentId.length() > 160
                    || !currency.matches("[A-Z]{3}") || !signatureSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Verified Stripe payment event is invalid");
            }
        }
    }
}
