package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the official Stripe Java adapter against the local WireMock Stripe provider. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class StripeJavaPaymentProviderIntegrationTests {
    @Test
    void createsPaymentIntentThroughStripeCompatibleProvider() {
        var provider = new StripeJavaPaymentProvider(
                "sk_test_nexa_integration",
                "whsec_local_service_foundation",
                System.getProperty("nexa.stripe.api-base-url", "http://127.0.0.1:12111"));

        StripePaymentProvider.PaymentIntent intent = provider.createPaymentIntent(
                new StripePaymentProvider.PaymentIntentRequest(
                        1250,
                        "PEN",
                        "stripe-mock-" + UUID.randomUUID(),
                        Map.of("nexa_tenant_id", UUID.randomUUID().toString())));

        assertThat(intent.providerId()).startsWith("pi_mocknexa");
        assertThat(intent.clientSecret()).startsWith("pi_mocknexa");
        assertThat(intent.status()).isEqualTo("requires_action");

        var retrieved = provider.retrievePaymentIntent("pi_mock_nexa_retrieval");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.orElseThrow().providerId()).isEqualTo("pi_mock_nexa_retrieval");
    }
}
