package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

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
        var retried = mockMvc.perform(post("/api/v1/internal/organization-registrations/" + id + "/activation")
                        .header("X-Nexa-System-Operator", "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        assertThat(retried.getResponse().getContentAsString()).isEqualTo(activated.getResponse().getContentAsString());
        String tenantId = json(activated).get("tenantId").asText();
        String workspaceId = json(activated).get("workspaceId").asText();
        java.util.UUID tenantUuid = java.util.UUID.fromString(tenantId);
        java.util.UUID workspaceUuid = java.util.UUID.fromString(workspaceId);
        var tenantDatabaseTasks = jdbc.queryForList("select task_id from tenant_management.tenant_business_database_provisioning_task "
                + "where tenant_id=? and workspace_id=? and status='PENDING' order by task_id", java.util.UUID.class,
                tenantUuid, workspaceUuid);
        var workspaceAnchorTasks = jdbc.queryForList("select task_id from tenant_management.tenant_business_database_workspace_anchor_task "
                + "where tenant_id=? and workspace_id=? and status='PENDING' order by task_id", java.util.UUID.class,
                tenantUuid, workspaceUuid);
        assertThat(tenantDatabaseTasks).hasSize(1);
        assertThat(workspaceAnchorTasks).hasSize(1);
        var roles = jdbc.queryForList("select upper(r.code) from tenant_management.membership_role_definition a join tenant_management.role_definition r on r.id=a.role_id join tenant_management.workspace_membership m on m.id=a.membership_id where m.user_id=? order by r.code", String.class,
                java.util.UUID.fromString(json(activated).get("founderUserId").asText()));
        assertThat(roles).containsExactly("COMPANY_OWNER", "TENANT_ADMIN");
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace where id=? and tenant_id=?", Integer.class,
                java.util.UUID.fromString(workspaceId), java.util.UUID.fromString(tenantId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership where workspace_id=? and user_id='11111111-1111-4111-8111-111111111111'::uuid and membership_type='SYSTEM_WORKFLOW' and status='ACTIVE'", Integer.class,
                java.util.UUID.fromString(workspaceId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.membership_role_definition where membership_id=(select id from tenant_management.workspace_membership where workspace_id=? and user_id='11111111-1111-4111-8111-111111111111'::uuid)", Integer.class,
                java.util.UUID.fromString(workspaceId))).isEqualTo(1);
        var organization = jdbc.queryForMap("select legal_name,display_name,business_identifier,operation_category from tenant_management.organization_settings where tenant_id=?",
                java.util.UUID.fromString(tenantId));
        assertThat(organization).containsEntry("legal_name", "Integration Cold Chain")
                .containsEntry("display_name", "Integration Cold Chain")
                .containsEntry("business_identifier", "IT-" + slug)
                .containsEntry("operation_category", "b2bColdChainDistributor");
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.tenant where id=?", Integer.class,
                java.util.UUID.fromString(tenantId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from iam.security_notification_outbox where notification_type='PASSWORD_RESET' and recipient=?", Integer.class,
                email)).isEqualTo(1);
        assertThat(jdbc.queryForList("select task_id from tenant_management.tenant_business_database_provisioning_task "
                + "where tenant_id=? and workspace_id=? and status='PENDING' order by task_id", java.util.UUID.class,
                tenantUuid, workspaceUuid)).containsExactlyElementsOf(tenantDatabaseTasks);
        assertThat(jdbc.queryForList("select task_id from tenant_management.tenant_business_database_workspace_anchor_task "
                + "where tenant_id=? and workspace_id=? and status='PENDING' order by task_id", java.util.UUID.class,
                tenantUuid, workspaceUuid)).containsExactlyElementsOf(workspaceAnchorTasks);
    }

    @Test
    void operatorActivationAllowsFounderWithBuyerMembershipInAnotherTenant() throws Exception {
        String slug = "it-act-buyer-" + uuid().substring(0, 8);
        java.util.UUID buyerUserId = jdbc.queryForObject("select u.id from iam.user_account u "
                        + "join tenant_management.workspace_membership m on m.user_id=u.id "
                        + "join tenant_management.workspace w on w.id=m.workspace_id "
                        + "where u.normalized_email=? and w.slug=? and m.membership_type='BUYER' and m.status='ACTIVE'",
                java.util.UUID.class, BUYER_EMAIL, WORKSPACE_SLUG);
        java.util.UUID buyerTenantId = java.util.UUID.fromString(tenantId());
        java.util.UUID buyerWorkspaceId = java.util.UUID.fromString(workspaceId());

        var submitted = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations")
                        .header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(OrganizationRegistrationIT.payload(slug, BUYER_EMAIL)))
                .andExpect(status().isOk()).andReturn();
        String registrationId = json(submitted).get("registrationId").asText();
        var activated = mockMvc.perform(post("/api/v1/internal/organization-registrations/" + registrationId + "/activation")
                        .header("X-Nexa-System-Operator", "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();

        java.util.UUID founderUserId = java.util.UUID.fromString(json(activated).get("founderUserId").asText());
        java.util.UUID activatedTenantId = java.util.UUID.fromString(json(activated).get("tenantId").asText());
        java.util.UUID activatedWorkspaceId = java.util.UUID.fromString(json(activated).get("workspaceId").asText());
        assertThat(founderUserId).isEqualTo(buyerUserId);
        assertThat(activatedTenantId).isNotEqualTo(buyerTenantId);
        assertThat(activatedWorkspaceId).isNotEqualTo(buyerWorkspaceId);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership "
                        + "where workspace_id=? and user_id=? and membership_type='INTERNAL' and status='ACTIVE'",
                Integer.class, activatedWorkspaceId, founderUserId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership "
                        + "where workspace_id=? and user_id=? and membership_type='BUYER' and status='ACTIVE'",
                Integer.class, buyerWorkspaceId, founderUserId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership "
                        + "where workspace_id=? and user_id=?",
                Integer.class, activatedWorkspaceId, founderUserId)).isEqualTo(1);
    }
}
