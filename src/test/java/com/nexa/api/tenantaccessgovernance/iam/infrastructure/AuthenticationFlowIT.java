package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class AuthenticationFlowIT extends PostgresIntegrationSupport {
    @Test void browserAuthenticationBoundariesRequireAllowedOrigin() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInPayload()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInPayload()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInPayload().replace(TEST_PASSWORD, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test void workspacePreviewUsesSameBrowserOriginBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/auth/workspace-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceSlug\":\"icisa-test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/workspace-previews")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceSlug\":\"icisa-test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/workspace-previews")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceSlug\":\"icisa-test\"}"))
                .andExpect(status().isOk());
    }

    @Test void refreshCookieRequiresAllowedOriginAndKeepsConfiguredBoundaryAttributes() throws Exception {
        var login = mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInPayload()))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        org.assertj.core.api.Assertions.assertThat(setCookie)
                .contains("NEXA_PLATFORM_REFRESH=")
                .contains("HttpOnly")
                .doesNotContain("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/authentication");
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .cookie(new Cookie("NEXA_PLATFORM_REFRESH", cookieValue)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .cookie(new Cookie("NEXA_PLATFORM_REFRESH", cookieValue)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header("X-Nexa-Surface", "PORTAL")
                        .cookie(new Cookie("NEXA_PLATFORM_REFRESH", cookieValue)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header("X-Nexa-Surface", "PLATFORM")
                        .cookie(new Cookie("NEXA_PLATFORM_REFRESH", cookieValue)))
                .andExpect(status().isOk());
    }

    @Test void nativeAuthenticationUsesExplicitHeaderTransportWithoutWeakeningBrowserOriginRules() throws Exception {
        var login = mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("X-Nexa-Client", "NATIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInPayload()))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = login.getResponse().getHeader("X-Nexa-Refresh-Token");
        org.assertj.core.api.Assertions.assertThat(refreshToken).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(login.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        var refresh = mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header("X-Nexa-Client", "NATIVE")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header("X-Nexa-Refresh-Token", refreshToken))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(refresh.getResponse().getHeader("X-Nexa-Refresh-Token"))
                .isNotBlank()
                .isNotEqualTo(refreshToken);
        org.assertj.core.api.Assertions.assertThat(refresh.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        mockMvc.perform(post("/api/v1/auth/workspace-previews")
                        .header("X-Nexa-Client", "NATIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceSlug\":\"icisa-test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void nativeSignOutRevokesTheSessionWithoutEmittingBrowserCookies() throws Exception {
        var login = mockMvc.perform(post("/api/v1/authentication/sign-in")
                        .header("X-Nexa-Client", "NATIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signInPayload()))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = tools.jackson.databind.json.JsonMapper.shared()
                .readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/authentication/sign-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("X-Nexa-Client", "NATIVE"))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test void signInSessionAndSignOutUseRealSessionLifecycle() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/authentication/sign-in").header("Origin", ALLOWED_ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"wrong\",\"workspaceSlug\":\"icisa-test\",\"surface\":\"PLATFORM\"}" )).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/authentication/sign-out").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-Nexa-Surface", "PLATFORM").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void bearerAuthenticatedApiCommandDoesNotRequireBrowserOrigin() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(get("/api/v1/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test void signOutRemainsAvailableAfterMembershipSuspension() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        String membershipId = membershipId(SALES_EMAIL);
        jdbc.update("update tenant_management.workspace_membership set status='DISABLED' where id=?",
                java.util.UUID.fromString(membershipId));
        try {
            mockMvc.perform(post("/api/v1/authentication/sign-out")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("Origin", ALLOWED_ORIGIN))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?",
                    java.util.UUID.fromString(membershipId));
        }
    }

    @Test void signOutRejectsForeignOriginBeforeBearerSessionRevocation() throws Exception {
        String token = accessToken(SALES_EMAIL, "PLATFORM");
        mockMvc.perform(post("/api/v1/authentication/sign-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/sign-out")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent());
    }

    @Test void nativeIdentitySignInAutomaticallyEstablishesTheOnlyCurrentContextAndProjectsNames() throws Exception {
        String payload = "{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"surface\":\"PLATFORM\"}";
        mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header("X-Nexa-Client", "NATIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        MvcResult login = mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                        .header("X-Nexa-Client", "NATIVE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        var response = tools.jackson.databind.json.JsonMapper.shared().readTree(login.getResponse().getContentAsString());
        assertThat(response.get("outcome").asText()).isEqualTo("AUTHENTICATED");
        assertThat(response.get("session").get("accessToken").asText()).isNotBlank();
        String accessToken = response.get("session").get("accessToken").asText();
        String refreshToken = login.getResponse().getHeader("X-Nexa-Refresh-Token");
        assertThat(refreshToken).isNotBlank();
        assertThat(login.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        var current = mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        var session = tools.jackson.databind.json.JsonMapper.shared().readTree(current.getResponse().getContentAsString());
        assertThat(session.at("/tenant/tenantName").asText()).isEqualTo("ICISA Test");
        assertThat(session.at("/workspace/workspaceName").asText()).isEqualTo("ICISA Test Workspace");

        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header("X-Nexa-Client", "NATIVE")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header("X-Nexa-Refresh-Token", refreshToken))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test void nativeIdentitySignInReturnsNoWorkContextWhenEveryMembershipIsInactive() throws Exception {
        String membership = membershipId(SALES_EMAIL);
        jdbc.update("update tenant_management.workspace_membership set status='DISABLED' where id=?",
                java.util.UUID.fromString(membership));
        try {
            var result = mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                            .header("X-Nexa-Client", "NATIVE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                                    + "\",\"surface\":\"PLATFORM\"}"))
                    .andExpect(status().isForbidden())
                    .andReturn();
            var problem = tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
            assertThat(problem.get("code").asText()).isEqualTo("NO_WORK_CONTEXT");
        } finally {
            jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?",
                    java.util.UUID.fromString(membership));
        }
    }

    @Test void nativeIdentitySelectionRevalidatesMembershipConsumesTicketOnceAndPreservesUnrelatedSession() throws Exception {
        CreatedContext secondContext = createSecondWorkspaceMembership(SALES_EMAIL);
        String extraMembershipId = secondContext.membershipId();
        try {
            String unrelatedAccessToken = accessToken(SALES_EMAIL, "PLATFORM");
            String payload = "{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                    + "\",\"surface\":\"PLATFORM\"}";
            MvcResult identity = mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                            .header("X-Nexa-Client", "NATIVE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andReturn();
            var identityResponse = tools.jackson.databind.json.JsonMapper.shared()
                    .readTree(identity.getResponse().getContentAsString());
            assertThat(identityResponse.get("outcome").asText()).isEqualTo("ACCESS_CONTEXT_SELECTION_REQUIRED");
            assertThat(identityResponse.get("session").isNull()).isTrue();
            String ticket = identityResponse.get("accessContextTicket").asText();
            assertThat(ticket).isNotBlank();
            assertThat(identity.getResponse().getHeader("X-Nexa-Refresh-Token")).isNull();
            assertThat(identityResponse.toString()).doesNotContain("accessToken");

            var listed = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Access-Context-Ticket", ticket))
                    .andExpect(status().isOk())
                    .andReturn();
            var contexts = tools.jackson.databind.json.JsonMapper.shared().readTree(listed.getResponse().getContentAsString())
                    .get("accessContexts");
            assertThat(contexts).hasSize(2);
            assertThat(contexts.toString()).contains("tenantName", "workspaceName", extraMembershipId);

            jdbc.update("update tenant_management.workspace_membership set status='SUSPENDED' where id=?",
                    java.util.UUID.fromString(extraMembershipId));
            try {
                mockMvc.perform(post("/api/v1/me/access-context-selections")
                                .header("X-Nexa-Client", "NATIVE")
                                .header("X-Nexa-Surface", "PLATFORM")
                                .header("X-Nexa-Access-Context-Ticket", ticket)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"membershipId\":\"" + extraMembershipId + "\"}"))
                        .andExpect(status().isForbidden());
            } finally {
                jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?",
                        java.util.UUID.fromString(extraMembershipId));
            }

            Integer previousSessions = jdbc.queryForObject("select count(*) from iam.refresh_session s "
                    + "join iam.user_account u on u.id=s.user_id where u.normalized_email=?", Integer.class, SALES_EMAIL);
            MvcResult selected = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Access-Context-Ticket", ticket)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + extraMembershipId + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            var session = tools.jackson.databind.json.JsonMapper.shared().readTree(selected.getResponse().getContentAsString());
            assertThat(session.get("session").get("membershipId").asText()).isEqualTo(extraMembershipId);
            assertThat(selected.getResponse().getHeader("X-Nexa-Refresh-Token")).isNotBlank();
            Integer currentSessions = jdbc.queryForObject("select count(*) from iam.refresh_session s "
                    + "join iam.user_account u on u.id=s.user_id where u.normalized_email=?", Integer.class, SALES_EMAIL);
            assertThat(currentSessions).isEqualTo(previousSessions + 1);
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + unrelatedAccessToken))
                    .andExpect(status().isOk());

            var replay = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Access-Context-Ticket", ticket)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + extraMembershipId + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertThat(replay.getResponse().getContentAsString()).doesNotContain(ticket);
            Integer afterReplay = jdbc.queryForObject("select count(*) from iam.refresh_session s "
                    + "join iam.user_account u on u.id=s.user_id where u.normalized_email=?", Integer.class, SALES_EMAIL);
            assertThat(afterReplay).isEqualTo(currentSessions);
        } finally {
            deleteSecondWorkspace(secondContext);
        }
    }

    @Test void concurrentNativeSelectionsConsumeOneTicketAndCreateAtMostOneSession() throws Exception {
        CreatedContext secondContext = createSecondWorkspaceMembership(SALES_EMAIL);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String payload = "{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                    + "\",\"surface\":\"PLATFORM\"}";
            MvcResult identity = mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                            .header("X-Nexa-Client", "NATIVE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andReturn();
            String ticket = tools.jackson.databind.json.JsonMapper.shared()
                    .readTree(identity.getResponse().getContentAsString()).get("accessContextTicket").asText();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var selection = (java.util.concurrent.Callable<MvcResult>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent selection gate timed out");
                return mockMvc.perform(post("/api/v1/me/access-context-selections")
                                .header("X-Nexa-Client", "NATIVE")
                                .header("X-Nexa-Surface", "PLATFORM")
                                .header("X-Nexa-Access-Context-Ticket", ticket)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"membershipId\":\"" + secondContext.membershipId() + "\"}"))
                        .andReturn();
            };
            var first = executor.submit(selection);
            var second = executor.submit(selection);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);
            MvcResult rejected = firstResult.getResponse().getStatus() == 401 ? firstResult : secondResult;
            assertThat(rejected.getResponse().getContentAsString()).doesNotContain(ticket);
            Integer sessions = jdbc.queryForObject("select count(*) from iam.refresh_session where membership_id=?",
                    Integer.class, java.util.UUID.fromString(secondContext.membershipId()));
            assertThat(sessions).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            deleteSecondWorkspace(secondContext);
        }
    }

    private CreatedContext createSecondWorkspaceMembership(String email) {
        java.util.UUID user = jdbc.queryForObject("select id from iam.user_account where normalized_email=?", java.util.UUID.class, email);
        java.util.UUID sourceMembership = java.util.UUID.fromString(membershipId(email));
        java.util.UUID tenant = java.util.UUID.randomUUID();
        java.util.UUID extraWorkspace = java.util.UUID.randomUUID();
        java.util.UUID extraMembership = java.util.UUID.randomUUID();
        String tenantSlug = "identity-tenant-" + tenant.toString().substring(0, 8);
        String slug = "identity-" + extraWorkspace.toString().substring(0, 8);
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenant, "Second identity tenant", tenantSlug);
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) "
                        + "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                extraWorkspace, tenant, "Second identity workspace", slug);
        jdbc.update("insert into tenant_management.workspace_membership "
                        + "(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "
                        + "select ?,?,user_id,membership_type,'ACTIVE',current_timestamp,current_timestamp,0 "
                        + "from tenant_management.workspace_membership where id=?",
                extraMembership, extraWorkspace, sourceMembership);
        jdbc.update("insert into tenant_management.membership_role_definition "
                        + "(membership_id,tenant_id,workspace_id,role_id,assigned_at) "
                        + "select ?,?,?,role_id,current_timestamp from tenant_management.membership_role_definition "
                        + "where membership_id=?",
                extraMembership, tenant, extraWorkspace, sourceMembership);
        return new CreatedContext(tenant.toString(), extraWorkspace.toString(), extraMembership.toString());
    }

    private void deleteSecondWorkspace(CreatedContext context) {
        jdbc.update("delete from iam.refresh_session where membership_id=?", java.util.UUID.fromString(context.membershipId()));
        jdbc.update("delete from tenant_management.workspace_membership where id=?", java.util.UUID.fromString(context.membershipId()));
        jdbc.update("delete from tenant_management.workspace where id=?", java.util.UUID.fromString(context.workspaceId()));
        jdbc.update("delete from tenant_management.tenant where id=?", java.util.UUID.fromString(context.tenantId()));
    }

    private record CreatedContext(String tenantId, String workspaceId, String membershipId) { }

    private String signInPayload() {
        return "{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"workspaceSlug\":\"" + WORKSPACE_SLUG + "\",\"surface\":\"PLATFORM\"}";
    }
}
