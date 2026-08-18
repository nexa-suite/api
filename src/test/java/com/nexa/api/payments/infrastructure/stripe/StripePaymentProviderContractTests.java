package com.nexa.api.payments.infrastructure.stripe;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripePaymentProviderContractTests {
    private static final String SECRET = "whsec_contract";

    @Test
    void localProviderHonoursIdempotencyAndReturnsResponseOnlySecret() {
        var provider = new DeterministicLocalStripePaymentProvider(SECRET);
        var request = new StripePaymentProvider.PaymentIntentRequest(1250, "PEN", "payment-key", Map.of("nexa_tenant_id", UUID.randomUUID().toString()));

        var first = provider.createPaymentIntent(request);
        var retry = provider.createPaymentIntent(request);

        assertThat(retry.providerId()).isEqualTo(first.providerId());
        assertThat(retry.clientSecret()).isEqualTo(first.clientSecret());
        assertThat(first.clientSecret()).isNotBlank();
    }

    @Test
    void webhookContractRequiresFreshSignatureAndPreservesTenantMetadata() {
        var provider = new DeterministicLocalStripePaymentProvider(SECRET);
        String tenant = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String payload = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"payment_intent_id\":\"pi_1\",\"status\":\"succeeded\",\"amount\":1250,\"currency\":\"pen\",\"nexa_tenant_id\":\"" + tenant + "\",\"nexa_workspace_id\":\"" + workspace + "\"}";
        long timestamp = Instant.now().getEpochSecond();

        var event = provider.verifyWebhook(payload, signature(timestamp, payload));

        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.paymentIntentId()).isEqualTo("pi_1");
        assertThat(event.metadata()).containsEntry("nexa_tenant_id", tenant).containsEntry("nexa_workspace_id", workspace);
        assertThatThrownBy(() -> provider.verifyWebhook(payload, signature(timestamp - 301, payload)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void officialStripeAdapterVerifiesTheRawStripeEventContract() {
        var provider = new StripeJavaPaymentProvider("sk_test_contract", SECRET);
        String tenant = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        String payload = """
                {"id":"evt_stripe_contract","object":"event","api_version":"2024-06-20","created":1710000000,
                 "data":{"object":{"id":"pi_contract","object":"payment_intent","amount":1250,"amount_capturable":0,
                 "amount_received":1250,"capture_method":"automatic","confirmation_method":"automatic",
                 "created":1710000000,"currency":"pen","livemode":false,"metadata":{"nexa_tenant_id":"%s",
                 "nexa_workspace_id":"%s"},"payment_method_types":["card"],"status":"succeeded"}},
                 "livemode":false,"pending_webhooks":1,"request":null,"type":"payment_intent.succeeded"}
                """.formatted(tenant, workspace).replaceAll("\\s+", "");

        var event = provider.verifyWebhook(payload, signature(Instant.now().getEpochSecond(), payload));

        assertThat(event.eventId()).isEqualTo("evt_stripe_contract");
        assertThat(event.paymentIntentId()).isEqualTo("pi_contract");
        assertThat(event.amountMinor()).isEqualTo(1250L);
        assertThat(event.metadata()).containsEntry("nexa_tenant_id", tenant).containsEntry("nexa_workspace_id", workspace);
    }

    private static String signature(long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String digest = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return "t=" + timestamp + ",v1=" + digest;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
