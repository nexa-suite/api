package com.nexa.api.payments.application.port;

import java.util.Map;
import java.util.Optional;

public interface StripePaymentProvider {
    default boolean supportsPaymentIntentCreation() { return true; }
    default boolean supportsBuyerPaymentMethodSetup() { return false; }
    default boolean supportsLocalSetupIntentConfirmation() { return false; }
    PaymentIntent createPaymentIntent(PaymentIntentRequest request);
    default Optional<PaymentIntent> retrievePaymentIntent(String providerId) { return Optional.empty(); }
    /**
     * Confirms an intent through the configured Stripe-compatible provider.
     * The local browser acceptance harness uses this only with the explicit
     * stripe provider profile; production callers use Stripe.js directly.
     */
    default PaymentIntent confirmPaymentIntent(String providerId) {
        throw new UnsupportedOperationException("Payment provider does not support confirmation");
    }
    /** Attempts a compensating refund for a captured provider payment. */
    default Refund refundPayment(String providerId, long amountMinor, String currency, String idempotencyKey) {
        throw new UnsupportedOperationException("Payment provider does not support refunds");
    }
    default ProviderCustomer createCustomer(CustomerRequest request) {
        throw new UnsupportedOperationException("Payment provider does not support customer creation");
    }
    default Optional<ProviderCustomer> retrieveCustomer(String providerId) { return Optional.empty(); }
    default ProviderSetupIntent createSetupIntent(SetupIntentRequest request) {
        throw new UnsupportedOperationException("Payment provider does not support SetupIntents");
    }
    default Optional<ProviderSetupIntent> retrieveSetupIntent(String providerId) { return Optional.empty(); }
    default Optional<ProviderPaymentMethod> retrievePaymentMethod(String providerId) { return Optional.empty(); }
    default ProviderPaymentMethod detachPaymentMethod(String providerId) {
        throw new UnsupportedOperationException("Payment provider does not support detaching payment methods");
    }
    default SignedProviderWebhook confirmLocalSetupIntent(String providerSetupIntentId) {
        throw new UnsupportedOperationException("Payment provider does not support local SetupIntent confirmation");
    }
    StripeWebhookEvent verifyWebhook(String payload, String signature);

    record PaymentIntentRequest(long amountMinor, String currency, String idempotencyKey, Map<String, String> metadata) { }
    record PaymentIntent(String providerId, String clientSecret, String status) { }
    record Refund(String providerRefundId, String status) { }
    record CustomerRequest(String idempotencyKey, Map<String, String> metadata) {
        public CustomerRequest { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record ProviderCustomer(String providerId, Map<String, String> metadata) {
        public ProviderCustomer { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record SetupIntentRequest(String customerId, String idempotencyKey, Map<String, String> metadata) {
        public SetupIntentRequest { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record ProviderSetupIntent(String providerId, String customerId, String clientSecret, String status,
                               String paymentMethodId, Map<String, String> metadata) {
        public ProviderSetupIntent { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record ProviderPaymentMethod(String providerId, String customerId, String type, String brand, String last4,
                                 Long expiryMonth, Long expiryYear) { }
    record SignedProviderWebhook(String payload, String signature) { }
    record StripeWebhookEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                              Long amountMinor, String currency, Map<String, String> metadata,
                              String setupIntentId, String providerCustomerId, String providerPaymentMethodId) {
        public StripeWebhookEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                                  Long amountMinor, String currency) {
            this(eventId, eventType, paymentIntentId, paymentStatus, amountMinor, currency, Map.of(), null, null, null);
        }

        public StripeWebhookEvent(String eventId, String eventType, String paymentIntentId, String paymentStatus,
                                  Long amountMinor, String currency, Map<String, String> metadata) {
            this(eventId, eventType, paymentIntentId, paymentStatus, amountMinor, currency, metadata,
                    null, null, null);
        }

        public StripeWebhookEvent {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
