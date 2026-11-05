package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import com.nexa.api.payments.infrastructure.stripe.DeterministicLocalStripePaymentProvider;
import com.nexa.api.payments.infrastructure.stripe.StripePaymentWebhookSignatureVerifierAdapter;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicLocalStripePaymentProviderTests {
    @Test
    void onlySignedPayloadPreservesOpaquePaymentRoute() throws Exception {
        String secret = "test-only-webhook-secret";
        String route = UUID.randomUUID().toString();
        String payload = "{\"id\":\"evt-route\",\"type\":\"payment_intent.succeeded\","
                + "\"payment_intent_id\":\"pi-route\",\"status\":\"succeeded\","
                + "\"amount\":100,\"currency\":\"pen\",\"nexa_payment_route_id\":\"" + route + "\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        var verifier = new StripePaymentWebhookSignatureVerifierAdapter(
                new DeterministicLocalStripePaymentProvider(secret));

        assertThat(verifier.verify(payload, signature).paymentRouteReference()).isEqualTo(route);
        assertThatThrownBy(() -> verifier.verify(payload.replace(route, UUID.randomUUID().toString()), signature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stripe webhook signature is invalid");
    }

    @Test
    void localBuyerSetupUsesServerIssuedObjectsAndSignedWebhookWithoutCardInput() {
        String secret = "test-only-webhook-secret";
        var provider = new DeterministicLocalStripePaymentProvider(secret);
        Map<String, String> setupMetadata = Map.of(
                "nexa_tenant_id", UUID.randomUUID().toString(),
                "nexa_workspace_id", UUID.randomUUID().toString(),
                "nexa_buyer_membership_id", UUID.randomUUID().toString(),
                "nexa_client_account_id", UUID.randomUUID().toString(),
                "nexa_setup_attempt_id", UUID.randomUUID().toString());
        Map<String, String> customerMetadata = Map.of(
                "nexa_tenant_id", setupMetadata.get("nexa_tenant_id"),
                "nexa_workspace_id", setupMetadata.get("nexa_workspace_id"),
                "nexa_buyer_membership_id", setupMetadata.get("nexa_buyer_membership_id"),
                "nexa_client_account_id", setupMetadata.get("nexa_client_account_id"));
        var customer = provider.createCustomer(new StripePaymentProvider.CustomerRequest("customer-key", customerMetadata));
        var setup = provider.createSetupIntent(new StripePaymentProvider.SetupIntentRequest(customer.providerId(),
                "setup-key", setupMetadata));

        assertThat(provider.supportsBuyerPaymentMethodSetup()).isTrue();
        assertThat(setup.status()).isEqualTo("requires_payment_method");
        assertThat(setup.clientSecret()).isNotBlank();

        var signed = provider.confirmLocalSetupIntent(setup.providerId());
        var verified = provider.verifyWebhook(signed.payload(), signed.signature());
        var retrievedSetup = provider.retrieveSetupIntent(setup.providerId()).orElseThrow();
        var retrievedCustomer = provider.retrieveCustomer(customer.providerId()).orElseThrow();
        var retrievedMethod = provider.retrievePaymentMethod(retrievedSetup.paymentMethodId()).orElseThrow();

        assertThat(verified.eventType()).isEqualTo("setup_intent.succeeded");
        assertThat(verified.setupIntentId()).isEqualTo(setup.providerId());
        assertThat(verified.providerCustomerId()).isEqualTo(customer.providerId());
        assertThat(verified.providerPaymentMethodId()).isEqualTo(retrievedSetup.paymentMethodId());
        assertThat(verified.metadata()).isEqualTo(setupMetadata);
        assertThat(retrievedCustomer.metadata()).isEqualTo(customerMetadata);
        assertThat(retrievedMethod.type()).isEqualTo("card");
        assertThat(retrievedMethod.last4()).isEqualTo("4242");
        assertThat(signed.payload()).doesNotContain("card_number", "cvc", "4242424242424242");
        assertThat(provider.confirmLocalSetupIntent(setup.providerId()).payload()).isEqualTo(signed.payload());

        assertThatThrownBy(() -> provider.verifyWebhook(signed.payload() + " ", signed.signature()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stripe webhook signature is invalid");
    }
}
