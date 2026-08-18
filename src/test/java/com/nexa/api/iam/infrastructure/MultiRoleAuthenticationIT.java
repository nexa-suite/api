package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class MultiRoleAuthenticationIT extends NexaWorkflowIntegrationSupport {
    @Test
    void ownerAuthenticationExposesCanonicalRoleSetWithoutSingularAuthority() throws Exception {
        var result = mockMvc.perform(post("/api/v1/authentication/sign-in").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + OWNER_EMAIL + "\",\"password\":\"" + TEST_PASSWORD + "\",\"workspaceSlug\":\"" + WORKSPACE_SLUG + "\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = json(result).get("session");
        assertThat(session.get("roles").isArray()).isTrue();
        assertThat(session.get("roles").toString()).contains("TENANT_ADMIN", "COMPANY_OWNER");
        assertThat(session.has("role")).isFalse();
    }
}
