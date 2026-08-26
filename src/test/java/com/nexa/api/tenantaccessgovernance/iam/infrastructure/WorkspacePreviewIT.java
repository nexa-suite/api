package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class WorkspacePreviewIT extends NexaWorkflowIntegrationSupport {
    @Test void publicPreviewRecognizesOnlyActiveWorkspaceSlug() throws Exception {
        var recognized = mockMvc.perform(post("/api/v1/auth/workspace-previews").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"workspaceSlug\":\"icisa-test\"}"))
                .andExpect(status().isOk()).andReturn();
        var unknown = mockMvc.perform(post("/api/v1/auth/workspace-previews").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"workspaceSlug\":\"does-not-exist\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(recognized).get("recognized").asBoolean()).isTrue();
        assertThat(json(unknown).get("recognized").asBoolean()).isFalse();
    }
}
