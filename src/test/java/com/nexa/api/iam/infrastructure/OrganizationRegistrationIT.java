package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OrganizationRegistrationIT extends NexaWorkflowIntegrationSupport {
    @Test
    void submitsAndReadsRegistrationOnlyWithItsOpaqueStatusToken() throws Exception {
        String slug = "it-reg-" + uuid().substring(0, 8);
        var submitted = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations").header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(payload(slug, "it-founder-" + uuid() + "@example.test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_ACTIVATION")).andReturn();
        String id = json(submitted).get("registrationId").asText();
        String statusToken = json(submitted).get("statusToken").asText();
        assertThat(statusToken).isNotBlank();
        mockMvc.perform(get("/api/v1/tenant-management/organization-registrations/" + id).param("statusToken", statusToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_ACTIVATION"));
        mockMvc.perform(get("/api/v1/tenant-management/organization-registrations/" + id).param("statusToken", "wrong-token"))
                .andExpect(status().isNotFound());
    }

    protected static String payload(String slug, String email) {
        return "{\"legalName\":\"Integration Cold Chain\",\"displayName\":\"Integration Cold Chain\",\"businessIdentifier\":\"IT-" + slug + "\",\"operationCategory\":\"b2bColdChainDistributor\",\"storageSiteName\":\"Integration Store\",\"storageSiteAddress\":\"Lima\",\"founderEmail\":\"" + email + "\",\"founderDisplayName\":\"Integration Founder\",\"workspaceName\":\"Integration Workspace\",\"workspaceSlug\":\"" + slug + "\",\"referencePlan\":\"Starter\",\"termsVersion\":\"2026-01\",\"termsAccepted\":true}";
    }
}
