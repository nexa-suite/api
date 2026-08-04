package com.nexa.api.payments.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PaymentIntentSecurityIT extends PaymentIntegrationSupport {
    @Test
    void paymentIntentDerivesAmountAndReturnsSecretOnlyAtCreation() throws Exception {
        PaymentIntentFixture fixture = createCardPayment();

        assertThat(fixture.clientSecret()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='payments' and table_name='payment' and column_name='client_secret'", Integer.class)).isZero();

        MvcResult detail = mockMvc.perform(get("/api/v1/payments/" + fixture.paymentId())
                        .header("Authorization", "Bearer " + fixture.buyerToken()))
                .andExpect(status().isOk()).andReturn();
        assertThat(detail.getResponse().getContentAsString()).doesNotContain("clientSecret");
        assertThat(jdbc.queryForObject("select amount from payments.payment where id=?", java.math.BigDecimal.class, java.util.UUID.fromString(fixture.paymentId())))
                .isEqualByComparingTo(fixture.receivable().amount());
    }

    @Test
    void paymentIntentRequiresAnIdempotencyKey() throws Exception {
        OpenReceivable receivable = createOpenReceivable();
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");

        mockMvc.perform(post("/api/v1/receivables/" + receivable.id() + "/payment-intents")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isBadRequest());
    }
}
