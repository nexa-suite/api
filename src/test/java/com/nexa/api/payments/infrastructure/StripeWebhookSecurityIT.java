package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class StripeWebhookSecurityIT extends PaymentIntegrationSupport {
    @Test
    void invalidRawSignatureIsRejectedBeforeQueueInsertion() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        String payload = stripePayload("evt-security-" + uuid(), "payment_intent.succeeded", fixture.providerPaymentIntentId(),
                "succeeded", fixture.receivable().amount().movePointRight(2).longValueExact(), fixture.receivable().currency(), tenantId(), workspaceId());

        mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType("application/json")
                        .header("Stripe-Signature", stripeSignature(Instant.now().getEpochSecond(), payload) + "x")
                        .content(payload))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from payments.stripe_event_inbox where payment_intent_id=?", Integer.class, fixture.providerPaymentIntentId())).isZero();
    }

    @Test
    void paymentWebhookRequiresTenantAndWorkspaceBinding() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        String payload = "{\"id\":\"evt-unbound-" + uuid() + "\",\"type\":\"payment_intent.succeeded\",\"payment_intent_id\":\""
                + fixture.providerPaymentIntentId() + "\",\"status\":\"succeeded\",\"amount\":100,\"currency\":\"pen\"}";

        mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType("application/json")
                        .header("Stripe-Signature", stripeSignature(payload))
                        .content(payload))
                .andExpect(status().isBadRequest());
    }
}
