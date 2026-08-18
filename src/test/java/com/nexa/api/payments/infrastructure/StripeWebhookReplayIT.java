package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class StripeWebhookReplayIT extends PaymentIntegrationSupport {
    @Test
    void sameSignedStripeEventIsAcceptedOnce() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        String eventId = "evt-replay-" + uuid();
        String payload = stripePayload(eventId, "payment_intent.processing", fixture.providerPaymentIntentId(),
                "processing", fixture.receivable().amount().movePointRight(2).longValueExact(), fixture.receivable().currency(), tenantId(), workspaceId());
        String signature = stripeSignature(payload);

        String first = mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", signature).content(payload))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", signature).content(payload))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(first).contains("ACCEPTED");
        assertThat(second).contains("DUPLICATE");
        assertThat(jdbc.queryForObject("select count(*) from payments.stripe_event_inbox where event_id=?", Integer.class, eventId)).isEqualTo(1);
    }
}
