package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.iam.infrastructure.notification.JdbcSecurityNotificationOutboxAdapter;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = "nexa.security.notification-outbox.poll-delay=PT1H")
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class TenantAdministrationIT extends PostgresIntegrationSupport {
    @Autowired
    private JdbcSecurityNotificationOutboxAdapter notificationOutbox;

    @Test
    void organizationSettingsAreTypedAuditedAndOptimisticallyConcurrent() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        MvcResult profile = mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        String etag = profile.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();

        mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"legalName\":\"ICISA Test\",\"displayName\":\"ICISA Administration\",\"businessIdentifier\":\"IT-ADMIN\",\"operationCategory\":\"B2B_COLD_CHAIN_DISTRIBUTOR\"}"))
                .andExpect(status().isPreconditionRequired());
        MvcResult updated = mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"legalName\":\"ICISA Test\",\"displayName\":\"ICISA Administration\",\"businessIdentifier\":\"IT-ADMIN\",\"operationCategory\":\"B2B_COLD_CHAIN_DISTRIBUTOR\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(updated.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        mockMvc.perform(patch("/api/v1/organization").header("Authorization", "Bearer " + owner).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"legalName\":\"ICISA Test\",\"displayName\":\"Stale\",\"businessIdentifier\":\"IT-ADMIN\",\"operationCategory\":\"B2B_COLD_CHAIN_DISTRIBUTOR\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/settings/regional").header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/units").header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/security").header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/access-matrix").header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/plan-usage").header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/settings/regional").header("Authorization", "Bearer " + owner).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"timezone\":\"UTC\",\"language\":\"xx\",\"currency\":\"USD\",\"countryRegion\":\"PE\",\"dateTimePolicy\":\"LOCALE\",\"locale\":\"en-US\"}"))
                .andExpect(status().isBadRequest());

        String buyer = accessToken(BUYER_EMAIL, "PORTAL");
        mockMvc.perform(get("/api/v1/organization").header("Authorization", "Bearer " + buyer)).andExpect(status().isForbidden());
        String pureOwner = createPureOwner();
        String securityEtag = mockMvc.perform(get("/api/v1/settings/security").header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(patch("/api/v1/settings/security").header("Authorization", "Bearer " + pureOwner).header("If-Match", securityEtag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"passwordMinLength\":12,\"sessionDurationMinutes\":480,\"invitationExpirationHours\":72,\"requiredEmailDomain\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void workspacesAreIdempotentUniqueAndProtectTheFinalAdministrativeMembership() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String slug = "admin-" + uuid().substring(0, 8);
        String key = "workspace-" + uuid();
        MvcResult created = mockMvc.perform(post("/api/v1/workspaces").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Administration Workspace\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String workspaceId = json(created).get("id").asText();
        String workspaceEtag = created.getResponse().getHeader("ETag");
        MvcResult repeated = mockMvc.perform(post("/api/v1/workspaces").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Administration Workspace\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json(repeated).get("id").asText()).isEqualTo(workspaceId);
        mockMvc.perform(post("/api/v1/workspaces").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Other\",\"slug\":\"other-" + uuid().substring(0, 8) + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/workspaces/" + workspaceId).header("Authorization", "Bearer " + owner).header("If-Match", workspaceEtag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Administration Workspace Updated\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/suspensions").header("Authorization", "Bearer " + owner).header("If-Match", "\"1\""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/reactivations").header("Authorization", "Bearer " + owner).header("If-Match", "\"2\""))
                .andExpect(status().isOk());

        String ownerMembership = jdbc.queryForObject("select m.id::text from tenant_management.workspace_membership m join iam.user_account u on u.id=m.user_id where u.normalized_email=? and m.workspace_id=?", String.class, OWNER_EMAIL, UUID.fromString(workspaceId()));
        String ownerMembershipVersion = jdbc.queryForObject("select version::text from tenant_management.workspace_membership where id=?", String.class, UUID.fromString(ownerMembership));
        mockMvc.perform(patch("/api/v1/workspace-memberships/" + ownerMembership + "/roles").header("Authorization", "Bearer " + owner).header("If-Match", "\"" + ownerMembershipVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"COMPANY_OWNER\"]}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/workspaces/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner)).andExpect(status().isNotFound());

        String raceSlug = "race-" + uuid().substring(0, 8);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> createWorkspaceStatus(owner, raceSlug, "race-a-" + uuid()),
                    () -> createWorkspaceStatus(owner, raceSlug, "race-b-" + uuid()));
            List<Integer> statuses = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void invitationsPersistOnlyHashesCoordinateOutboxAndRejectReplay() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String email = "invited-" + uuid().substring(0, 8) + "@example.test";
        String key = "invitation-" + uuid();
        String body = "{\"email\":\"" + email + "\",\"displayName\":\"Invited Operator\",\"roles\":[\"SALES\",\"WAREHOUSE\"]}";
        MvcResult created = mockMvc.perform(post("/api/v1/organization-invitations").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        String invitationId = json(created).get("id").asText();
        assertThat(created.getResponse().getContentAsString()).doesNotContain("token");
        String encrypted = jdbc.queryForObject("select payload_ciphertext from iam.security_notification_outbox where notification_type='ORGANIZATION_INVITATION' and recipient=? order by created_at desc limit 1", String.class, email);
        String hash = jdbc.queryForObject("select token_hash from tenant_management.organization_invitation where id=?", String.class, UUID.fromString(invitationId));
        assertThat(hash).hasSize(64);
        assertThat(encrypted).isNotBlank().doesNotContain(email);
        String payload = notificationOutbox.decrypt(encrypted);
        String token = payload.substring(payload.indexOf("token=") + 6, payload.indexOf('\n', payload.indexOf("token=")));
        assertThat(token).isNotBlank();
        MvcResult repeated = mockMvc.perform(post("/api/v1/organization-invitations").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        assertThat(json(repeated).get("id").asText()).isEqualTo(invitationId);
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"wrong\",\"password\":\"integration-test-password\",\"displayName\":\"Wrong\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"integration-test-password\",\"displayName\":\"Invited Operator\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"integration-test-password\",\"displayName\":\"Invited Operator\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.membership_role_assignment r join tenant_management.workspace_membership m on m.id=r.membership_id join iam.user_account u on u.id=m.user_id where u.normalized_email=? and r.role='SALES'", Integer.class, email)).isEqualTo(1);
    }

    private int createWorkspaceStatus(String owner, String slug, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/workspaces").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Race\",\"slug\":\"" + slug + "\"}")).andReturn().getResponse().getStatus();
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    private String createPureOwner() throws Exception {
        String email = "pure-owner-" + uuid().substring(0, 8) + "@example.test";
        UUID userId = UUID.randomUUID(); UUID membershipId = UUID.randomUUID();
        UUID tenant = UUID.fromString(tenantId()); UUID workspace = UUID.fromString(workspaceId());
        String passwordHash = jdbc.queryForObject("select password_hash from iam.password_credential c join iam.user_account u on u.id=c.user_id where u.normalized_email=?", String.class, OWNER_EMAIL);
        String username = "pure_owner_" + userId.toString().replace("-", "").substring(0, 12);
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)", userId, email, email, username, username, "Pure Owner", "en");
        jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) values (?,?,'bcrypt',current_timestamp)", userId, passwordHash);
        jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',current_timestamp,current_timestamp,0)", membershipId, workspace, userId);
        jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) values (?,?,?,'COMPANY_OWNER',current_timestamp)", membershipId, tenant, workspace);
        return accessToken(email, "PLATFORM");
    }
}
