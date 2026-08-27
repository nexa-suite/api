package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.port.PaymentPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PaymentConcurrencyIT extends PaymentIntegrationSupport {
    @Autowired
    private PaymentPort paymentPort;

    @Test
    void concurrentPaymentIntentRetriesProduceOnePayment() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String idempotencyKey = "concurrent-payment-intent-" + uuid();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> paymentIntent(buyer, receivable.id(), idempotencyKey));
            Future<MvcResult> second = executor.submit(() -> paymentIntent(buyer, receivable.id(), idempotencyKey));
            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(json(firstResult).get("paymentId").asText()).isEqualTo(json(secondResult).get("paymentId").asText());
            assertThat(jdbc.queryForObject("select count(*) from payments.payment where tenant_id=? and workspace_id=? and receivable_id=? and idempotency_key=?", Integer.class,
                    tenantUuid(), workspaceUuid(), receivable.id(), idempotencyKey)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void idempotentPaymentIntentReplaysAfterTheWebhookClosesTheReceivable() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String idempotencyKey = "payment-intent-replay-" + uuid();
        MvcResult first = paymentIntent(buyer, receivable.id(), idempotencyKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        var value = json(first);
        String eventId = "evt-payment-replay-" + uuid();
        String payload = stripePayload(eventId, "payment_intent.succeeded", value.get("providerPaymentIntentId").asText(),
                "succeeded", receivable.amount().movePointRight(2).longValueExact(), receivable.currency(), tenantId(), workspaceId());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", stripeSignature(payload)).content(payload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        paymentPort.processStripeWebhookInbox();

        MvcResult replay = paymentIntent(buyer, receivable.id(), idempotencyKey);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(replay).get("paymentId").asText()).isEqualTo(value.get("paymentId").asText());
        assertThat(jdbc.queryForObject("select status from payments.receivable where id=?", String.class, receivable.id())).isEqualTo("PAID");
    }

    private MvcResult paymentIntent(String buyer, java.util.UUID receivableId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/receivables/" + receivableId + "/payment-intents")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idempotencyKey))
                .andReturn();
    }
}
