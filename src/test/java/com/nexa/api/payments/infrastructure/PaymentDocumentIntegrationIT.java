package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.port.PaymentPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PaymentDocumentIntegrationIT extends PaymentIntegrationSupport {
    @Autowired
    private PaymentPort paymentPort;

    @Test
    void successfulPaymentSettlesReceivableAndRequestsPaymentReceipt() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();
        String eventId = "evt-document-" + uuid();
        String payload = stripePayload(eventId, "payment_intent.succeeded", fixture.providerPaymentIntentId(), "succeeded",
                fixture.receivable().amount().movePointRight(2).longValueExact(), fixture.receivable().currency(), tenantId(), workspaceId());

        mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).header("Stripe-Signature", stripeSignature(payload)).content(payload))
                .andExpect(status().isAccepted());
        paymentPort.processStripeWebhookInbox();

        assertThat(jdbc.queryForObject("select status from payments.payment where id=?", String.class, java.util.UUID.fromString(fixture.paymentId()))).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select amount_paid from payments.receivable where id=?", java.math.BigDecimal.class, fixture.receivable().id()))
                .isEqualByComparingTo(fixture.receivable().amount());
        assertThat(jdbc.queryForObject("select count(*) from business_documents.document_generation_request where subject_type='PAYMENT' and subject_id=? and document_type='PAYMENT_RECEIPT'", Integer.class, java.util.UUID.fromString(fixture.paymentId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments.payment_event where payment_id=? and event_key=?", Integer.class, java.util.UUID.fromString(fixture.paymentId()), eventId)).isEqualTo(1);
    }
}
