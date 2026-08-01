package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class AuthenticationFlowIT extends PostgresIntegrationSupport {
    @Test void signInSessionAndSignOutUseRealSessionLifecycle() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/authentication/sign-in").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"wrong\",\"workspaceSlug\":\"icisa-test\",\"surface\":\"PLATFORM\"}" )).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/authentication/sign-out").header("Authorization", "Bearer " + token).header("X-Nexa-Surface", "PLATFORM").header("Origin", ALLOWED_ORIGIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
