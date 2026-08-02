package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class MultiRoleChangeFeedIT extends NexaWorkflowIntegrationSupport {
    @Test
    void organizationMembershipRepresentationKeepsAllRoles() throws Exception {
        String token = accessToken(OWNER_EMAIL, "PLATFORM");
        var result = mockMvc.perform(get("/api/v1/workspace-memberships").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var memberships = json(result);
        assertThat(memberships.toString()).contains("TENANT_ADMIN", "COMPANY_OWNER");
        assertThat(memberships.toString()).doesNotContain("\"role\"");
    }
}
