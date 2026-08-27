package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.port.PaymentPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class StripeWebhookReplayIT extends PaymentIntegrationSupport {
    @Autowired
    private PaymentPort paymentPort;

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

    @Test
    void staleStripeEventAtRetryLimitIsDeadLettered() {
        String eventId = "evt-dead-letter-" + uuid();
        Instant expired = Instant.now().minusSeconds(60);
        UUID claimToken = UUID.randomUUID();
        try {
            jdbc.update("insert into payments.stripe_event_inbox "
                            + "(event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,tenant_id,workspace_id,signature_sha256,status,attempt_count,received_at,next_attempt_at,processing_started_at,lease_until,claim_token) "
                            + "values (?,?,?,?,?,?,?,?,?,'PROCESSING',10,?,?,?,?,?)",
                    eventId, "payment_intent.processing", "pi-dead-letter-" + uuid(), "processing", 100L, "PEN",
                    tenantUuid(), workspaceUuid(), "a".repeat(64), Timestamp.from(expired), Timestamp.from(expired),
                    Timestamp.from(expired), Timestamp.from(expired), claimToken);

            paymentPort.processStripeWebhookInbox();

            assertThat(jdbc.queryForObject("select status from payments.stripe_event_inbox where event_id=?", String.class, eventId))
                    .isEqualTo("DEAD_LETTER");
        } finally {
            jdbc.update("delete from payments.stripe_event_inbox where event_id=?", eventId);
        }
    }
}
