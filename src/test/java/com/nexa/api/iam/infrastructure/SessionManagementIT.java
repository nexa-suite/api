package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SessionManagementIT extends NexaWorkflowIntegrationSupport {
    @Test
    void listsRevokesOthersAndRevokesOneOwnSessionOnly() throws Exception {
        String first = accessToken(SALES_EMAIL, "PLATFORM");
        String second = accessToken(SALES_EMAIL, "PLATFORM");
        var listed = mockMvc.perform(get("/api/v1/me/sessions").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andReturn();
        var sessions = json(listed).get("sessions");
        assertThat(sessions.size()).isGreaterThanOrEqualTo(2);
        mockMvc.perform(post("/api/v1/me/session-revocations").header("Authorization", "Bearer " + first))
                .andExpect(status().isNoContent());
        var afterOthers = mockMvc.perform(get("/api/v1/me/sessions").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(afterOthers).get("sessions").size()).isEqualTo(1);
        var secondList = mockMvc.perform(get("/api/v1/me/sessions").header("Authorization", "Bearer " + second))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(secondList.getResponse().getStatus()).isEqualTo(401);
    }
}
