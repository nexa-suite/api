package com.nexa.api.payments.application.publicapi.provider;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Narrow trusted runtime capability for creating intents and reconciling provider refunds. */
public interface PaymentProviderOperations {
    boolean supportsPaymentIntentCreation();

    default boolean supportsBuyerPaymentMethodSetup() { return false; }
    default boolean supportsLocalSetupIntentConfirmation() { return false; }

    PaymentIntent createPaymentIntent(PaymentIntentRequest request);

    Refund refundPayment(String providerId, long amountMinor, String currency, String idempotencyKey);

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

    record PaymentIntentRequest(long amountMinor, String currency, String idempotencyKey,
            UUID paymentRouteId, UUID walletRechargeId, UUID tenantId, UUID workspaceId) { }

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
}
