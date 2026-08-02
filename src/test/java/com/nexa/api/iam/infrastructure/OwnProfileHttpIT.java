package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OwnProfileHttpIT extends PostgresIntegrationSupport {
    @Test
    void authenticatedUserReadsAndOptimisticallyUpdatesOwnProfile() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        var current = mockMvc.perform(get("/api/v1/me/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(OWNER_EMAIL)).andReturn();
        String etag = current.getResponse().getHeader("ETag");
        mockMvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + token).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ICISA Owner\",\"phone\":\"+51987654321\",\"preferredLanguage\":\"es\",\"timezone\":\"America/Lima\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("ICISA Owner"));
        mockMvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + token).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"ICISA Owner\",\"phone\":\"invalid\",\"preferredLanguage\":\"es\",\"timezone\":\"America/Lima\"}"))
                .andExpect(status().isBadRequest());
    }
}
