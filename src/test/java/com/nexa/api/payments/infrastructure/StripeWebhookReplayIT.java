package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.publicapi.PaymentWebhookInboxWorker;
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
    private PaymentWebhookInboxWorker inboxWorker;

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
        assertThat(jdbc.queryForObject("select payment_route_id is not null and tenant_id is null "
                + "and workspace_id is null from payments.stripe_event_inbox where event_id=?",
                Boolean.class, eventId)).isTrue();
        assertThat(tenantJdbc().queryForObject("select count(*) from payments.stripe_event_inbox where event_id=?",
                Integer.class, eventId)).isZero();
    }

    @Test
    void staleStripeEventAtRetryLimitIsDeadLettered() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        UUID routeId = jdbc.queryForObject("select route_id from tenant_management.payment_provider_route "
                + "where provider_payment_intent_id=?", UUID.class, fixture.providerPaymentIntentId());
        String eventId = "evt-dead-letter-" + uuid();
        Instant expired = Instant.now().minusSeconds(60);
        UUID claimToken = UUID.randomUUID();
        try {
            jdbc.update("insert into payments.stripe_event_inbox "
                            + "(event_id,event_type,payment_intent_id,payment_status,amount_minor,currency,payment_route_id,signature_sha256,status,attempt_count,received_at,next_attempt_at,processing_started_at,lease_until,claim_token) "
                            + "values (?,?,?,?,?,?,?,?,'PROCESSING',10,?,?,?,?,?)",
                    eventId, "payment_intent.processing", fixture.providerPaymentIntentId(), "processing",
                    fixture.receivable().amount().movePointRight(2).longValueExact(), fixture.receivable().currency(),
                    routeId, "a".repeat(64), Timestamp.from(expired), Timestamp.from(expired),
                    Timestamp.from(expired), Timestamp.from(expired), claimToken);

            inboxWorker.processPending();

            assertThat(jdbc.queryForObject("select status from payments.stripe_event_inbox where event_id=?", String.class, eventId))
                    .isEqualTo("DEAD_LETTER");
        } finally {
            jdbc.update("delete from payments.stripe_event_inbox where event_id=?", eventId);
        }
    }
}
