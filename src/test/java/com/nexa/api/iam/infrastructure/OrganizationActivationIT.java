package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class OrganizationActivationIT extends NexaWorkflowIntegrationSupport {
    @Test
    void operatorActivationCreatesTenantWorkspaceFounderMembershipAndFixedRoleSet() throws Exception {
        String slug = "it-act-" + uuid().substring(0, 8);
        String email = "it-founder-" + uuid() + "@example.test";
        var submitted = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(OrganizationRegistrationIT.payload(slug, email))).andExpect(status().isOk()).andReturn();
        String id = json(submitted).get("registrationId").asText();
        var activated = mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        String tenantId = json(activated).get("tenantId").asText();
        String workspaceId = json(activated).get("workspaceId").asText();
        var roles = jdbc.queryForList("select upper(r.code) from tenant_management.membership_role_definition a join tenant_management.role_definition r on r.id=a.role_id join tenant_management.workspace_membership m on m.id=a.membership_id where m.user_id=? order by r.code", String.class,
                java.util.UUID.fromString(json(activated).get("founderUserId").asText()));
        assertThat(roles).containsExactly("COMPANY_OWNER", "TENANT_ADMIN");
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace where id=? and tenant_id=?", Integer.class,
                java.util.UUID.fromString(workspaceId), java.util.UUID.fromString(tenantId))).isEqualTo(1);
    }
}
