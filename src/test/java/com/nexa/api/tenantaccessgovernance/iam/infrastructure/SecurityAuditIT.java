package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SecurityAuditIT extends PostgresIntegrationSupport {
    private static final String OPERATOR_TOKEN = "integration-system-operator-token-0123456789-abcdefghijklmnopqrstuvwxyz";
    private static final List<String> REQUIRED_EVENTS = List.of(
            "LOGIN_SUCCEEDED", "LOGIN_FAILED", "AUTHENTICATION_THROTTLED", "PASSWORD_RESET_REQUESTED",
            "PASSWORD_RESET_COMPLETED", "PASSWORD_CHANGED", "SESSION_REVOKED", "ALL_OTHER_SESSIONS_REVOKED",
            "ROLE_ASSIGNMENT_CHANGED", "MEMBERSHIP_SUSPENDED", "MEMBERSHIP_REACTIVATED",
            "ORGANIZATION_REGISTRATION_SUBMITTED", "ORGANIZATION_ACTIVATED", "ORGANIZATION_REJECTED",
            "SENSITIVE_AUTHORIZATION_DENIED", "SYSTEM_OPERATOR_AUTHENTICATED", "SYSTEM_OPERATOR_AUTHENTICATION_FAILED");

    @BeforeEach
    void clearSecurityThrottleFixtures() {
        jdbc.update("delete from iam.authentication_failure");
        jdbc.update("delete from iam.password_reset_throttle_bucket");
        jdbc.update("delete from iam.system_operator_throttle_bucket");
    }

    @AfterEach
    void restoreSeedSecurityFixtures() {
        restorePassword(OWNER_EMAIL);
        restorePassword(BUYER_EMAIL);
        jdbc.update("delete from iam.password_reset_request where normalized_email=?", BUYER_EMAIL);
        restoreMembership(OWNER_EMAIL, "TENANT_ADMIN", "COMPANY_OWNER");
        restoreMembership(SALES_EMAIL, "SALES");
    }

    @Test
    void resetRequestAppendsAuditWithoutPersistingRawToken() throws Exception {
        Map<String, Long> before = auditCounts();
        String correlation = "audit-reset-request-" + uuid();
        String email = "audit-" + uuid() + "@example.test";
        mockMvc.perform(post("/api/v1/auth/password-reset-requests")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Correlation-ID", correlation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"surface\":\"PORTAL\"}"))
                        .andExpect(status().isOk());
        assertAuditDelta(before, "PASSWORD_RESET_REQUESTED", 1);
        assertExactAuditDeltas(before, auditCounts(), Map.of("PASSWORD_RESET_REQUESTED", 1L));
        assertThat(jdbc.queryForObject("select count(*) from iam.security_audit_event where event_type='PASSWORD_RESET_REQUESTED' and correlation_id=? and metadata_json->>'accountResponse'='generic'",
                Long.class, correlation)).isEqualTo(1L);
    }

    @Test
    void authenticationAndCredentialLifecycleEventsHaveExactCounts() throws Exception {
        Map<String, Long> before = auditCounts();

        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("User-Agent", "audit-login-failed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"unknown-" + uuid() + "@example.test\",\"password\":\"wrong\",\"workspaceSlug\":\"icisa-test\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isUnauthorized());

        String throttledIdentifier = "audit-throttled-" + uuid() + "@example.test";
        String throttleAgent = "audit-throttle-agent";
        String throttleRemote = "audit-throttle-" + uuid();
        String throttleFingerprint = sha256(throttleRemote + "|" + throttleAgent);
        Instant now = Instant.now();
        jdbc.update("insert into iam.authentication_failure (id,normalized_identifier,client_fingerprint,failure_count,window_started_at,last_failure_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), throttledIdentifier, throttleFingerprint, 5, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("User-Agent", throttleAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setRemoteAddr(throttleRemote);
                            return request;
                        })
                        .content("{\"identifier\":\"" + throttledIdentifier + "\",\"password\":\"wrong\",\"workspaceSlug\":\"icisa-test\",\"surface\":\"PLATFORM\"}"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/auth/password-reset-requests")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Correlation-ID", "audit-reset-request-lifecycle-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown-reset-" + uuid() + "@example.test\",\"surface\":\"PORTAL\"}"))
                .andExpect(status().isOk());

        String resetToken = "audit-reset-token-" + uuid();
        String resetHash = sha256(resetToken);
        jdbc.update("insert into iam.password_reset_request (id,normalized_email,surface,token_hash,status,attempts,expires_at,created_at) values (gen_random_uuid(),?,'PORTAL',?,'PENDING',0,?,?)",
                BUYER_EMAIL, resetHash, java.sql.Timestamp.from(now.plusSeconds(1800)), java.sql.Timestamp.from(now));
        mockMvc.perform(post("/api/v1/auth/password-resets")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Correlation-ID", "audit-reset-completed-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + resetToken + "\",\"newPassword\":\"audit-reset-password-2026\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/me/password-changes")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Correlation-ID", "audit-password-changed-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + TEST_PASSWORD + "\",\"newPassword\":\"audit-password-change-2026\"}"))
                .andExpect(status().isNoContent());

        Map<String, Long> after = auditCounts();
        assertAuditDelta(before, after, "LOGIN_SUCCEEDED", 1);
        assertAuditDelta(before, after, "LOGIN_FAILED", 1);
        assertAuditDelta(before, after, "AUTHENTICATION_THROTTLED", 1);
        assertAuditDelta(before, after, "PASSWORD_RESET_REQUESTED", 1);
        assertAuditDelta(before, after, "PASSWORD_RESET_COMPLETED", 1);
        assertAuditDelta(before, after, "PASSWORD_CHANGED", 1);
        assertExactAuditDeltas(before, after, Map.of(
                "LOGIN_SUCCEEDED", 1L,
                "LOGIN_FAILED", 1L,
                "AUTHENTICATION_THROTTLED", 1L,
                "PASSWORD_RESET_REQUESTED", 1L,
                "PASSWORD_RESET_COMPLETED", 1L,
                "PASSWORD_CHANGED", 1L));
    }

    @Test
    void sessionAndMembershipLifecycleEventsHaveExactCounts() throws Exception {
        Map<String, Long> before = auditCounts();
        String firstSessionToken = accessToken(SALES_EMAIL, "PLATFORM");
        accessToken(SALES_EMAIL, "PLATFORM");

        mockMvc.perform(post("/api/v1/me/session-revocations")
                        .header("Authorization", "Bearer " + firstSessionToken)
                        .header("X-Correlation-ID", "audit-all-other-sessions-" + uuid()))
                .andExpect(status().isNoContent());
        MvcResult sessions = mockMvc.perform(get("/api/v1/me/sessions").header("Authorization", "Bearer " + firstSessionToken))
                .andExpect(status().isOk()).andReturn();
        String currentSessionId = json(sessions).at("/sessions/0/sessionId").asText();
        mockMvc.perform(delete("/api/v1/me/sessions/" + currentSessionId)
                        .header("Authorization", "Bearer " + firstSessionToken)
                        .header("X-Correlation-ID", "audit-session-revoked-" + uuid()))
                .andExpect(status().isNoContent());

        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        String targetMembership = membershipId(SALES_EMAIL);
        MvcResult currentMembership = mockMvc.perform(get("/api/v1/workspace-memberships/" + targetMembership)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        MvcResult roleChange = mockMvc.perform(patch("/api/v1/workspace-memberships/" + targetMembership + "/roles")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("If-Match", currentMembership.getResponse().getHeader("ETag"))
                        .header("X-Correlation-ID", "audit-role-assignment-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"SALES\",\"LOGISTICS\"]}"))
                .andExpect(status().isOk()).andReturn();
        MvcResult suspended = mockMvc.perform(post("/api/v1/workspace-memberships/" + targetMembership + "/suspensions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("If-Match", roleChange.getResponse().getHeader("ETag"))
                        .header("X-Correlation-ID", "audit-membership-suspended-" + uuid()))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/v1/workspace-memberships/" + targetMembership + "/reactivations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("If-Match", suspended.getResponse().getHeader("ETag"))
                        .header("X-Correlation-ID", "audit-membership-reactivated-" + uuid()))
                .andExpect(status().isOk());

        Map<String, Long> after = auditCounts();
        assertAuditDelta(before, after, "ALL_OTHER_SESSIONS_REVOKED", 1);
        assertAuditDelta(before, after, "SESSION_REVOKED", 1);
        assertAuditDelta(before, after, "ROLE_ASSIGNMENT_CHANGED", 1);
        assertAuditDelta(before, after, "MEMBERSHIP_SUSPENDED", 1);
        assertAuditDelta(before, after, "MEMBERSHIP_REACTIVATED", 1);
        assertExactAuditDeltas(before, after, Map.of(
                "LOGIN_SUCCEEDED", 3L,
                "ALL_OTHER_SESSIONS_REVOKED", 1L,
                "SESSION_REVOKED", 1L,
                "ROLE_ASSIGNMENT_CHANGED", 1L,
                "MEMBERSHIP_SUSPENDED", 1L,
                "MEMBERSHIP_REACTIVATED", 1L));
    }

    @Test
    void authorizationOrganizationAndOperatorEventsHaveExactCounts() throws Exception {
        Map<String, Long> before = auditCounts();
        String ownerToken = accessToken(OWNER_EMAIL, "PLATFORM");
        jdbc.update("delete from tenant_management.membership_role_definition a using tenant_management.role_definition r "
                + "where a.role_id=r.id and a.membership_id=(select id from tenant_management.workspace_membership where user_id=(select id from iam.user_account where normalized_email=?) and workspace_id=(select id from tenant_management.workspace where slug=?)) and r.code='company_owner'", OWNER_EMAIL, WORKSPACE_SLUG);
        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("X-Correlation-ID", "audit-sensitive-denied-" + uuid()))
                .andExpect(status().isForbidden());

        String firstSlug = "audit-act-" + uuid().substring(0, 8);
        String firstEmail = "audit-act-" + uuid() + "@example.test";
        MvcResult submitted = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Correlation-ID", "audit-registration-submitted-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload(firstSlug, firstEmail)))
                .andExpect(status().isOk()).andReturn();
        String registrationId = json(submitted).get("registrationId").asText();
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + registrationId + "/activation")
                        .header("X-Correlation-ID", "audit-organization-activated-" + uuid())
                        .header("X-Nexa-System-Operator", OPERATOR_TOKEN))
                .andExpect(status().isOk());

        String secondSlug = "audit-reject-" + uuid().substring(0, 8);
        String secondEmail = "audit-reject-" + uuid() + "@example.test";
        MvcResult rejectedSubmission = mockMvc.perform(post("/api/v1/tenant-management/organization-registrations")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Correlation-ID", "audit-registration-rejected-submitted-" + uuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationPayload(secondSlug, secondEmail)))
                .andExpect(status().isOk()).andReturn();
        String rejectedId = json(rejectedSubmission).get("registrationId").asText();
        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + rejectedId + "/rejection")
                        .header("X-Correlation-ID", "audit-organization-rejected-" + uuid())
                        .header("X-Nexa-System-Operator", OPERATOR_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"integration audit coverage\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/organization-registrations/" + uuid() + "/activation")
                        .header("X-Correlation-ID", "audit-operator-failed-" + uuid())
                        .header("X-Nexa-System-Operator", "invalid-operator-credential"))
                .andExpect(status().isForbidden());

        Map<String, Long> after = auditCounts();
        assertAuditDelta(before, after, "SENSITIVE_AUTHORIZATION_DENIED", 1);
        assertAuditDelta(before, after, "ORGANIZATION_REGISTRATION_SUBMITTED", 2);
        assertAuditDelta(before, after, "ORGANIZATION_ACTIVATED", 1);
        assertAuditDelta(before, after, "ORGANIZATION_REJECTED", 1);
        assertAuditDelta(before, after, "SYSTEM_OPERATOR_AUTHENTICATED", 2);
        assertAuditDelta(before, after, "SYSTEM_OPERATOR_AUTHENTICATION_FAILED", 1);
        assertExactAuditDeltas(before, after, Map.of(
                "LOGIN_SUCCEEDED", 1L,
                "SENSITIVE_AUTHORIZATION_DENIED", 1L,
                "ORGANIZATION_REGISTRATION_SUBMITTED", 2L,
                "ORGANIZATION_ACTIVATED", 1L,
                "ORGANIZATION_REJECTED", 1L,
                "SYSTEM_OPERATOR_AUTHENTICATED", 2L,
                "SYSTEM_OPERATOR_AUTHENTICATION_FAILED", 1L));
    }

    private Map<String, Long> auditCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (String event : REQUIRED_EVENTS) {
            counts.put(event, jdbc.queryForObject("select count(*) from iam.security_audit_event where event_type=?", Long.class, event));
        }
        return counts;
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    private void assertAuditDelta(Map<String, Long> before, String event, long expected) {
        assertAuditDelta(before, auditCounts(), event, expected);
    }

    private void assertAuditDelta(Map<String, Long> before, Map<String, Long> after, String event, long expected) {
        assertThat(after.get(event) - before.get(event)).as("audit event " + event).isEqualTo(expected);
    }

    private void assertExactAuditDeltas(Map<String, Long> before, Map<String, Long> after, Map<String, Long> expected) {
        for (String event : REQUIRED_EVENTS) {
            assertThat(after.get(event) - before.get(event))
                    .as("exact audit delta for " + event)
                    .isEqualTo(expected.getOrDefault(event, 0L));
        }
    }

    private void restorePassword(String email) {
        jdbc.update("update iam.password_credential set password_hash=?,algorithm='bcrypt',changed_at=current_timestamp where user_id=(select id from iam.user_account where normalized_email=?)",
                new BCryptPasswordEncoder(12).encode(TEST_PASSWORD), email);
    }

    private void restoreMembership(String email, String... roles) {
        UUID id = UUID.fromString(membershipId(email));
        jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?", id);
        jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", id);
        for (String role : roles) {
            jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                    + "select m.id,w.tenant_id,m.workspace_id,r.id,current_timestamp from tenant_management.workspace_membership m "
                    + "join tenant_management.workspace w on w.id=m.workspace_id join tenant_management.role_definition r "
                    + "on r.tenant_id is null and r.code=lower(?) where m.id=?", role, id);
        }
    }

    private static String registrationPayload(String slug, String email) {
        return "{\"legalName\":\"Audit Integration Cold Chain\",\"displayName\":\"Audit Integration Cold Chain\","
                + "\"businessIdentifier\":\"IT-" + slug + "\",\"operationCategory\":\"b2bColdChainDistributor\","
                + "\"storageSiteName\":\"Audit Store\",\"storageSiteAddress\":\"Lima\",\"founderEmail\":\"" + email + "\","
                + "\"founderDisplayName\":\"Audit Founder\",\"workspaceName\":\"Audit Workspace\",\"workspaceSlug\":\"" + slug + "\","
                + "\"referencePlan\":\"Starter\",\"termsVersion\":\"2026-01\",\"termsAccepted\":true}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
