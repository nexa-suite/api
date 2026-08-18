package com.nexa.api.shared.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class ChangeFeedSessionRevocationIT extends PostgresIntegrationSupport {
    @Test void revokedAccessJwtCannotReuseProtectedResource() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/authentication/sign-out").header("Authorization", "Bearer " + token).header("X-Nexa-Surface", "PLATFORM").header("Origin", ALLOWED_ORIGIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/change-feed/stream").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }
}
