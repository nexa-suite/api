package com.nexa.api.payments.infrastructure;

import com.nexa.api.payments.application.port.StripePaymentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BuyerSavedPaymentMethodIT extends PaymentIntegrationSupport {
    @Autowired
    private StripePaymentProvider stripePaymentProvider;

    @Test
    void workerCanLockScopedRowsWithoutUpdateAccessToProviderIdentity() {
        assertThat(tenantJdbc().queryForObject("select has_column_privilege('nexa_payments_worker', "
                + "'payments.buyer_payment_provider_profile', 'lock_version', 'UPDATE')", Boolean.class)).isTrue();
        assertThat(tenantJdbc().queryForObject("select has_column_privilege('nexa_payments_worker', "
                + "'payments.buyer_saved_payment_method', 'is_default', 'UPDATE')", Boolean.class)).isTrue();
        assertThat(tenantJdbc().queryForObject("select has_column_privilege('nexa_payments_worker', "
                + "'payments.buyer_payment_provider_profile', 'provider_customer_id', 'UPDATE')", Boolean.class)).isFalse();
        assertThat(tenantJdbc().queryForObject("select has_column_privilege('nexa_payments_worker', "
                + "'payments.buyer_saved_payment_method', 'provider_payment_method_id', 'UPDATE')", Boolean.class)).isFalse();
    }

    @Test
    void buyerSetupIsIdempotentReplaysSignedCallbackAndMaintainsDefaultAcrossMethods() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String idempotencyKey = "buyer-saved-method-" + UUID.randomUUID();
        String setupPath = "/api/v1/buyer/payment-methods/setup-intents";

        MvcResult first = mockMvc.perform(post(setupPath)
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        String setupIntentId = json(first).get("setupIntentId").asText();
        assertThat(setupIntentId).isNotBlank();
        assertThat(json(first).get("clientSecret").asText()).isNotBlank();

        MvcResult replay = mockMvc.perform(post(setupPath)
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(json(replay).get("setupIntentId").asText()).isEqualTo(setupIntentId);

        mockMvc.perform(get("/api/v1/buyer/payment-methods").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$").isEmpty());

        String confirmationPath = setupPath + "/" + setupIntentId + "/local-confirmation";
        mockMvc.perform(post(confirmationPath).header("Authorization", "Bearer " + buyer))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        StripePaymentProvider.SignedProviderWebhook callbackReplay =
                stripePaymentProvider.confirmLocalSetupIntent(setupIntentId);
        MvcResult replayReceipt = mockMvc.perform(post("/api/v1/integrations/stripe/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", callbackReplay.signature())
                        .content(callbackReplay.payload()))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(json(replayReceipt).get("status").asText()).isEqualTo("DUPLICATE");

        MvcResult listed = mockMvc.perform(get("/api/v1/buyer/payment-methods")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        var methods = json(listed);
        assertThat(methods.size()).isEqualTo(1);
        assertThat(methods.get(0).get("type").asText()).isEqualTo("CARD");
        assertThat(methods.get(0).get("brand").asText()).isEqualTo("visa");
        assertThat(methods.get(0).get("last4").asText()).isEqualTo("4242");
        assertThat(listed.getResponse().getContentAsString()).doesNotContain("providerPaymentMethodId", "clientSecret");
        String firstMethodId = methods.get(0).get("id").asText();
        String firstSetupKey = "buyer-saved-method-second-" + UUID.randomUUID();
        MvcResult secondSetup = mockMvc.perform(post(setupPath)
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", firstSetupKey))
                .andExpect(status().isAccepted()).andReturn();
        String secondIntentId = json(secondSetup).get("setupIntentId").asText();
        mockMvc.perform(post(setupPath + "/" + secondIntentId + "/local-confirmation")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        MvcResult twoMethods = mockMvc.perform(get("/api/v1/buyer/payment-methods")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        var twoMethodList = json(twoMethods);
        assertThat(twoMethodList.size()).isEqualTo(2);
        assertThat(twoMethodList.get(0).get("defaultMethod").asBoolean()).isTrue();
        assertThat(twoMethodList.get(0).get("id").asText()).isEqualTo(firstMethodId);
        assertThat(twoMethodList.get(1).get("defaultMethod").asBoolean()).isFalse();
        String secondMethodId = twoMethodList.get(1).get("id").asText();

        mockMvc.perform(patch("/api/v1/buyer/payment-methods/" + secondMethodId + "/default")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMethod").value(true));
        MvcResult switched = mockMvc.perform(get("/api/v1/buyer/payment-methods")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(switched).get(0).get("id").asText()).isEqualTo(secondMethodId);
        assertThat(json(switched).get(0).get("defaultMethod").asBoolean()).isTrue();
        assertThat(json(switched).get(1).get("id").asText()).isEqualTo(firstMethodId);
        assertThat(json(switched).get(1).get("defaultMethod").asBoolean()).isFalse();

        mockMvc.perform(delete("/api/v1/buyer/payment-methods/" + secondMethodId)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isNoContent());
        MvcResult afterRemoval = mockMvc.perform(get("/api/v1/buyer/payment-methods")
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(afterRemoval).size()).isEqualTo(1);
        assertThat(json(afterRemoval).get(0).get("id").asText()).isEqualTo(firstMethodId);
        assertThat(json(afterRemoval).get(0).get("defaultMethod").asBoolean()).isTrue();
        mockMvc.perform(delete("/api/v1/buyer/payment-methods/" + firstMethodId)
                        .header("Authorization", "Bearer " + buyer))
                .andExpect(status().isNoContent());
    }

    @Test
    void localConfirmationRejectsAnotherMembershipBeforeProviderSimulation() throws Exception {
        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        String sales = accessToken(SALES_EMAIL, "PLATFORM");
        MvcResult setup = mockMvc.perform(post("/api/v1/buyer/payment-methods/setup-intents")
                        .header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", "buyer-saved-method-owner-" + UUID.randomUUID()))
                .andExpect(status().isAccepted()).andReturn();
        String setupIntentId = json(setup).get("setupIntentId").asText();

        mockMvc.perform(post("/api/v1/buyer/payment-methods/setup-intents/" + setupIntentId + "/local-confirmation")
                        .header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());

        assertThat(tenantJdbc().queryForObject("select count(*) from payments.buyer_saved_payment_method", Integer.class))
                .isZero();
    }
}
