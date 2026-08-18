package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PaymentConcurrencyIT extends PaymentIntegrationSupport {
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

    private MvcResult paymentIntent(String buyer, java.util.UUID receivableId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/receivables/" + receivableId + "/payment-intents")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idempotencyKey))
                .andReturn();
    }
}
