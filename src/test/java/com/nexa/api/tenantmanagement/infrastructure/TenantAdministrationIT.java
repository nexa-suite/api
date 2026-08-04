package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.iam.infrastructure.notification.JdbcSecurityNotificationOutboxAdapter;
import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.tenantmanagement.infrastructure.InvitationExpirationJob;
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

    @Autowired
    private InvitationExpirationJob invitationExpirationJob;

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
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.membership_role_definition a join tenant_management.role_definition r on r.id=a.role_id join tenant_management.workspace_membership m on m.id=a.membership_id join iam.user_account u on u.id=m.user_id where u.normalized_email=? and r.code='sales'", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void invitationLifecycleRotatesRevokesAndSerializesConcurrentAcceptance() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String resendEmail = "resend-invited-" + uuid().substring(0, 8) + "@example.test";
        MvcResult resendCreated = createInvitation(owner, resendEmail, "Resend operator", "resend-" + uuid());
        String oldToken = invitationToken(resendEmail);
        MvcResult resent = mockMvc.perform(post("/api/v1/organization-invitations/" + json(resendCreated).get("id").asText() + "/resends")
                        .header("Authorization", "Bearer " + owner).header("If-Match", resendCreated.getResponse().getHeader("ETag")))
                .andExpect(status().isOk()).andReturn();
        String replacementToken = invitationToken(resendEmail);
        assertThat(replacementToken).isNotEqualTo(oldToken);
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + oldToken + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Old token\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + replacementToken + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Resend operator\"}"))
                .andExpect(status().isCreated());
        assertThat(json(resent).get("status").asText()).isEqualTo("PENDING");

        String revokeEmail = "revoke-invited-" + uuid().substring(0, 8) + "@example.test";
        MvcResult revokeCreated = createInvitation(owner, revokeEmail, "Revoke operator", "revoke-" + uuid());
        String revokedToken = invitationToken(revokeEmail);
        mockMvc.perform(post("/api/v1/organization-invitations/" + json(revokeCreated).get("id").asText() + "/revocations")
                        .header("Authorization", "Bearer " + owner).header("If-Match", revokeCreated.getResponse().getHeader("ETag")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + revokedToken + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Revoked operator\"}"))
                .andExpect(status().isNotFound());

        String concurrentEmail = "concurrent-invited-" + uuid().substring(0, 8) + "@example.test";
        MvcResult concurrentCreated = createInvitation(owner, concurrentEmail, "Concurrent operator", "concurrent-" + uuid());
        String concurrentToken = invitationToken(concurrentEmail);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
			List<Integer> statuses = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> acceptInvitationStatus(concurrentToken, "Concurrent operator"),
                    () -> acceptInvitationStatus(concurrentToken, "Concurrent operator"))).stream().map(future -> {
                        try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
                    }).toList();
            assertThat(statuses).contains(201).containsAnyOf(404, 409);
        } finally {
            executor.shutdownNow();
        }
        UUID concurrentInvitationId = UUID.fromString(json(concurrentCreated).get("id").asText());
        assertThat(jdbc.queryForObject("select status from tenant_management.organization_invitation where id=?", String.class, concurrentInvitationId)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m join iam.user_account u on u.id=m.user_id where u.normalized_email=?", Integer.class, concurrentEmail)).isEqualTo(1);
    }

    @Test
    void membershipLifecycleUsesIfMatchAndRevalidatesTheExistingSession() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String email = "lifecycle-member-" + uuid().substring(0, 8) + "@example.test";
        MvcResult created = createInvitation(owner, email, "Lifecycle member", "lifecycle-" + uuid());
        String token = invitationToken(email);
        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Lifecycle member\"}"))
                .andExpect(status().isCreated());
        String memberToken = accessToken(email, "PLATFORM");
        String membershipId = membershipId(email);
        MvcResult detail = mockMvc.perform(get("/api/v1/workspace-memberships/" + membershipId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        String version = detail.getResponse().getHeader("ETag");
        mockMvc.perform(post("/api/v1/workspace-memberships/" + membershipId + "/suspensions").header("Authorization", "Bearer " + owner).header("If-Match", version))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/workspace-memberships/" + membershipId + "/suspensions").header("Authorization", "Bearer " + owner).header("If-Match", version))
                .andExpect(status().isConflict());

        MvcResult suspended = mockMvc.perform(get("/api/v1/workspace-memberships/" + membershipId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/v1/workspace-memberships/" + membershipId + "/reactivations").header("Authorization", "Bearer " + owner).header("If-Match", suspended.getResponse().getHeader("ETag")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + memberToken)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/authentication/sign-out").header("Authorization", "Bearer " + memberToken).header("X-Nexa-Surface", "PLATFORM").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + memberToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void workspaceSettingsHaveOneCanonicalWarehouseStrategyAndCustomFieldsHaveAFullLifecycle() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String workspace = workspaceId();
        MvcResult workspaceSettings = mockMvc.perform(get("/api/v1/workspaces/" + workspace + "/settings").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        MvcResult operational = mockMvc.perform(get("/api/v1/workspaces/" + workspace + "/operational-settings").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(workspaceSettings).get("warehousePreferenceStrategy").asText())
                .isEqualTo(json(operational).get("defaultWarehouseSelectionPolicy").asText());
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='tenant_management' and table_name='workspace_settings' and column_name='warehouse_preference_strategy'", Integer.class)).isZero();

        String strategy = json(operational).get("defaultWarehouseSelectionPolicy").asText();
        String settingsBody = "{\"defaultWorkspaceBehavior\":\"STANDARD\",\"warehousePreferenceStrategy\":\"" + strategy + "\"}";
        mockMvc.perform(patch("/api/v1/workspaces/" + workspace + "/settings").header("Authorization", "Bearer " + owner)
                        .header("If-Match", workspaceSettings.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON).content(settingsBody))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select warehouse_preference_strategy from tenant_management.operational_settings where workspace_id=?", String.class, UUID.fromString(workspace))).isEqualTo(strategy);

        String fieldKey = "field" + uuid().replace("-", "").substring(0, 8);
        MvcResult field = mockMvc.perform(post("/api/v1/custom-field-definitions").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldKey\":\"" + fieldKey + "\",\"label\":\"Customer segment\",\"fieldKind\":\"TEXT\",\"scope\":\"CLIENT_ACCOUNT\",\"required\":true,\"uniqueValue\":true,\"displayOrder\":10,\"active\":true}"))
                .andExpect(status().isCreated()).andReturn();
        String fieldId = json(field).get("id").asText();
        MvcResult edited = mockMvc.perform(patch("/api/v1/custom-field-definitions/" + fieldId).header("Authorization", "Bearer " + owner)
                        .header("If-Match", field.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldKey\":\"" + fieldKey + "\",\"label\":\"Customer segment updated\",\"fieldKind\":\"TEXT\",\"scope\":\"CLIENT_ACCOUNT\",\"required\":true,\"uniqueValue\":true,\"displayOrder\":20,\"active\":true}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult deactivated = mockMvc.perform(post("/api/v1/custom-field-definitions/" + fieldId + "/deactivations").header("Authorization", "Bearer " + owner)
                        .header("If-Match", edited.getResponse().getHeader("ETag"))).andExpect(status().isOk()).andReturn();
        assertThat(mockMvc.perform(get("/api/v1/custom-field-definitions").header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).doesNotContain(fieldId);
        assertThat(mockMvc.perform(get("/api/v1/custom-field-definitions?includeInactive=true").header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains(fieldId);
        mockMvc.perform(post("/api/v1/custom-field-definitions/" + fieldId + "/activations").header("Authorization", "Bearer " + owner)
                        .header("If-Match", deactivated.getResponse().getHeader("ETag"))).andExpect(status().isOk());
    }

    @Test
    void existingUserInvitationAuthenticatesBeforeCreatingMembership() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String email = "existing-invited-" + uuid().substring(0, 8) + "@example.test";
        UUID userId = UUID.randomUUID();
        String username = "existing_invited_" + userId.toString().replace("-", "").substring(0, 12);
        String passwordHash = jdbc.queryForObject("select password_hash from iam.password_credential c join iam.user_account u on u.id=c.user_id where u.normalized_email=?", String.class, OWNER_EMAIL);
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                userId, email, email, username, username, "Existing invitee", "en");
        jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) values (?,?,'bcrypt',current_timestamp)", userId, passwordHash);

        String key = "existing-invitation-" + uuid();
        mockMvc.perform(post("/api/v1/organization-invitations").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"displayName\":\"Existing invitee\",\"roles\":[\"SALES\"]}"))
                .andExpect(status().isCreated());
        String encrypted = jdbc.queryForObject("select payload_ciphertext from iam.security_notification_outbox where notification_type='ORGANIZATION_INVITATION' and recipient=? order by created_at desc limit 1", String.class, email);
        String payload = notificationOutbox.decrypt(encrypted);
        String token = payload.substring(payload.indexOf("token=") + 6, payload.indexOf('\n', payload.indexOf("token=")));

        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"wrong-existing-password\",\"displayName\":\"Ignored\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership where user_id=?", Integer.class, userId)).isZero();

        mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"Ignored\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from tenant_management.workspace_membership where user_id=?", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void boundedInvitationJobExpiresPendingInvitations() throws Exception {
        String owner = accessToken(OWNER_EMAIL, "PLATFORM");
        String email = "expiring-invited-" + uuid().substring(0, 8) + "@example.test";
        MvcResult created = mockMvc.perform(post("/api/v1/organization-invitations").header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "expiry-invitation-" + uuid()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"displayName\":\"Expiring operator\",\"roles\":[\"WAREHOUSE\"]}"))
                .andExpect(status().isCreated()).andReturn();
        String invitationId = json(created).get("id").asText();
        jdbc.update("update tenant_management.organization_invitation set expires_at=current_timestamp - interval '1 minute' where id=?", UUID.fromString(invitationId));
        invitationExpirationJob.expireBatch();
        assertThat(jdbc.queryForObject("select status from tenant_management.organization_invitation where id=?", String.class, UUID.fromString(invitationId))).isEqualTo("EXPIRED");
    }

    private int createWorkspaceStatus(String owner, String slug, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/workspaces").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Race\",\"slug\":\"" + slug + "\"}")).andReturn().getResponse().getStatus();
    }

    private MvcResult createInvitation(String owner, String email, String displayName, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/organization-invitations").header("Authorization", "Bearer " + owner).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"displayName\":\"" + displayName + "\",\"roles\":[\"SALES\"]}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private String invitationToken(String recipient) {
        String encrypted = jdbc.queryForObject("select payload_ciphertext from iam.security_notification_outbox where notification_type='ORGANIZATION_INVITATION' and recipient=? order by created_at desc limit 1", String.class, recipient);
        String payload = notificationOutbox.decrypt(encrypted);
        int start = payload.indexOf("token=") + 6;
        return payload.substring(start, payload.indexOf('\n', start));
    }

    private int acceptInvitationStatus(String token, String displayName) throws Exception {
        return mockMvc.perform(post("/api/v1/organization-invitation-acceptances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + TEST_PASSWORD + "\",\"displayName\":\"" + displayName + "\"}"))
                .andReturn().getResponse().getStatus();
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
        jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                + "select ?,?,?,r.id,current_timestamp from tenant_management.role_definition r where r.tenant_id is null and r.code='company_owner'",
                membershipId, tenant, workspace);
        return accessToken(email, "PLATFORM");
    }
}
