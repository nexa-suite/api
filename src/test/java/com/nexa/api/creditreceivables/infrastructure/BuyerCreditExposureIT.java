package com.nexa.api.creditreceivables.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named="nexa.integration.enabled", matches="true")
class BuyerCreditExposureIT extends NexaWorkflowIntegrationSupport {
    @Test void currentBuyerCreditUsesTheSameServerResolvedAccountAsBuyerProfile() throws Exception {
        String token = accessToken(BUYER_EMAIL, "PORTAL");
        var account = mockMvc.perform(get("/api/v1/client-accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var credit = mockMvc.perform(get("/api/v1/client-accounts/me/credit-exposure")
                        .param("clientAccountId", "foreign-account").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(credit).get("clientAccountId").asText()).isEqualTo(json(account).get("id").asText());
        assertThat(json(credit).get("currency").asText()).isEqualTo("PEN");
        assertThat(json(credit).has("availableCredit")).isTrue();
    }
    @Test void requiresAuthenticationAndPortalScope() throws Exception {
        mockMvc.perform(get("/api/v1/client-accounts/me/credit-exposure")).andExpect(status().isUnauthorized());
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/client-accounts/me/credit-exposure").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
    }
}
