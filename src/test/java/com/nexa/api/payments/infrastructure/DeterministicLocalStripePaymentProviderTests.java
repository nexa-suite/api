package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.infrastructure.stripe.DeterministicLocalStripePaymentProvider;
import com.nexa.api.payments.infrastructure.stripe.StripePaymentWebhookSignatureVerifierAdapter;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

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
}
