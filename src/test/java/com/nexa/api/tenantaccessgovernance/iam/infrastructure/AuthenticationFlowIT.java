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
        assertThat(response.get("outcome").asText()).isEqualTo("SESSION_ESTABLISHED");
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
        Integer previousSessions = jdbc.queryForObject("select count(*) from iam.refresh_session where user_id=(select id from iam.user_account where normalized_email=?)", Integer.class, SALES_EMAIL);
        Integer previousTickets = jdbc.queryForObject("select count(*) from iam.access_context_selection_ticket where user_id=(select id from iam.user_account where normalized_email=?)", Integer.class, SALES_EMAIL);
        jdbc.update("update tenant_management.workspace_membership set status='DISABLED' where id=?",
                java.util.UUID.fromString(membership));
        try {
            var result = mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                            .header("X-Nexa-Client", "NATIVE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                                    + "\",\"surface\":\"PLATFORM\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            var response = tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
            assertThat(response.get("outcome").asText()).isEqualTo("NO_WORK_CONTEXT");
            assertThat(response.get("session").isNull()).isTrue();
            assertThat(response.get("ticketExpiresAt").isNull()).isTrue();
            assertThat(response.has("accessContextTicket")).isFalse();
            assertThat(result.getResponse().getHeader("X-Nexa-Context-Ticket")).isNull();
            assertThat(result.getResponse().getHeader("X-Nexa-Refresh-Token")).isNull();
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where user_id=(select id from iam.user_account where normalized_email=?)", Integer.class, SALES_EMAIL)).isEqualTo(previousSessions);
            assertThat(jdbc.queryForObject("select count(*) from iam.access_context_selection_ticket where user_id=(select id from iam.user_account where normalized_email=?)", Integer.class, SALES_EMAIL)).isEqualTo(previousTickets);
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
            assertThat(identityResponse.get("outcome").asText()).isEqualTo("CONTEXT_SELECTION_REQUIRED");
            assertThat(identityResponse.get("session").isNull()).isTrue();
            String ticket = identity.getResponse().getHeader("X-Nexa-Context-Ticket");
            assertThat(ticket).isNotBlank();
            assertThat(identityResponse.has("accessContextTicket")).isFalse();
            assertThat(identityResponse.toString()).doesNotContain(ticket);
            assertThat(identity.getResponse().getHeader("X-Nexa-Refresh-Token")).isNull();
            assertThat(identityResponse.toString()).doesNotContain("accessToken");

            var listed = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Context-Ticket", ticket))
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
                                .header("X-Nexa-Context-Ticket", ticket)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"membershipId\":\"" + extraMembershipId + "\"}"))
                        .andExpect(status().isConflict())
                        .andExpect(result -> assertThat(tools.jackson.databind.json.JsonMapper.shared()
                                .readTree(result.getResponse().getContentAsString()).get("code").asText())
                                .isEqualTo("ACCESS_CONTEXT_SELECTION_REJECTED"));
            } finally {
                jdbc.update("update tenant_management.workspace_membership set status='ACTIVE' where id=?",
                        java.util.UUID.fromString(extraMembershipId));
            }

            Integer previousSessions = jdbc.queryForObject("select count(*) from iam.refresh_session s "
                    + "join iam.user_account u on u.id=s.user_id where u.normalized_email=?", Integer.class, SALES_EMAIL);
            MvcResult selected = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Context-Ticket", ticket)
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
                            .header("X-Nexa-Context-Ticket", ticket)
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
            String ticket = identity.getResponse().getHeader("X-Nexa-Context-Ticket");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var selection = (java.util.concurrent.Callable<MvcResult>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent selection gate timed out");
                return mockMvc.perform(post("/api/v1/me/access-context-selections")
                                .header("X-Nexa-Client", "NATIVE")
                                .header("X-Nexa-Surface", "PLATFORM")
                                .header("X-Nexa-Context-Ticket", ticket)
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

    @Test void contextListAndSelectionRequireExactlyOneAuthorityMode() throws Exception {
        CreatedContext secondContext = createSecondWorkspaceMembership(SALES_EMAIL);
        try {
            MvcResult identity = mockMvc.perform(post("/api/v1/authentication/identity-sign-in")
                            .header("X-Nexa-Client", "NATIVE")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                                    + "\",\"surface\":\"PLATFORM\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String ticket = identity.getResponse().getHeader("X-Nexa-Context-Ticket");
            String bearer = accessToken(SALES_EMAIL, "PLATFORM");
            String selectionBody = "{\"membershipId\":\"" + secondContext.membershipId() + "\"}";

            var missingList = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(json(missingList).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            var mixedList = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Context-Ticket", ticket)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(json(mixedList).get("code").asText()).isEqualTo("INVALID_REQUEST");

            var missingSelection = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .contentType(MediaType.APPLICATION_JSON).content(selectionBody))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(json(missingSelection).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            var mixedSelection = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Context-Ticket", ticket)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(selectionBody))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(json(mixedSelection).get("code").asText()).isEqualTo("INVALID_REQUEST");

            var browserMissingList = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(json(browserMissingList).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            var browserMixedList = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Context-Ticket", ticket)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(json(browserMixedList).get("code").asText()).isEqualTo("INVALID_REQUEST");
            var browserMissingSelection = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .contentType(MediaType.APPLICATION_JSON).content(selectionBody))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(json(browserMissingSelection).get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            var browserMixedSelection = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header("X-Nexa-Context-Ticket", ticket)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(selectionBody))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(json(browserMixedSelection).get("code").asText()).isEqualTo("INVALID_REQUEST");
        } finally {
            deleteSecondWorkspace(secondContext);
        }
    }

    @Test void bearerContextSwitchRevokesOnlyTheInvokingFamily() throws Exception {
        CreatedContext secondContext = createSecondWorkspaceMembership(SALES_EMAIL);
        try {
            String invokingToken = accessToken(SALES_EMAIL, "PLATFORM");
            String unrelatedToken = accessToken(SALES_EMAIL, "PLATFORM");
            String invokingSession = sessionId(invokingToken);
            String unrelatedSession = sessionId(unrelatedToken);
            String invokingFamily = jdbc.queryForObject("select family_id::text from iam.refresh_session where id=?",
                    String.class, java.util.UUID.fromString(invokingSession));
            String unrelatedFamily = jdbc.queryForObject("select family_id::text from iam.refresh_session where id=?",
                    String.class, java.util.UUID.fromString(unrelatedSession));

            MvcResult selected = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header("X-Nexa-Client", "NATIVE")
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + invokingToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + secondContext.membershipId() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                    .andReturn();
            var selectedBody = json(selected);
            assertThat(selectedBody.at("/session/membershipId").asText()).isEqualTo(secondContext.membershipId());
            assertThat(selected.getResponse().getHeader("X-Nexa-Refresh-Token")).isNotBlank();
            assertThat(selected.getResponse().getContentAsString()).doesNotContain("refreshToken");

            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + invokingToken))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + unrelatedToken))
                    .andExpect(status().isOk());
            String replacementToken = selectedBody.get("accessToken").asText();
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + replacementToken))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where family_id=? and family_revoked_at is not null",
                    Integer.class, java.util.UUID.fromString(invokingFamily))).isGreaterThan(0);
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where family_id=? and family_revoked_at is not null",
                    Integer.class, java.util.UUID.fromString(unrelatedFamily))).isZero();
        } finally {
            deleteSecondWorkspace(secondContext);
        }
    }

    @Test void browserBearerContextSwitchUsesCookieTransportAndRevokesOnlyTheInvokingFamily() throws Exception {
        CreatedContext secondContext = createSecondWorkspaceMembership(SALES_EMAIL);
        try {
            MvcResult invokingLogin = mockMvc.perform(post("/api/v1/authentication/sign-in")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signInPayload()))
                    .andExpect(status().isOk())
                    .andReturn();
            String invokingToken = json(invokingLogin).get("accessToken").asText();
            String invokingRefreshCookie = cookieValue(invokingLogin, "NEXA_PLATFORM_REFRESH");
            String invokingSession = sessionId(invokingToken);
            String invokingFamily = jdbc.queryForObject("select family_id::text from iam.refresh_session where id=?",
                    String.class, java.util.UUID.fromString(invokingSession));

            String unrelatedToken = accessToken(SALES_EMAIL, "PLATFORM");
            String unrelatedSession = sessionId(unrelatedToken);
            String unrelatedFamily = jdbc.queryForObject("select family_id::text from iam.refresh_session where id=?",
                    String.class, java.util.UUID.fromString(unrelatedSession));

            MvcResult listed = mockMvc.perform(get("/api/v1/me/access-contexts")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + invokingToken))
                    .andExpect(status().isOk())
                    .andReturn();
            var options = json(listed).get("accessContexts");
            assertThat(options).hasSize(2);
            assertThat(options.toString()).contains(secondContext.membershipId());

            MvcResult selected = mockMvc.perform(post("/api/v1/me/access-context-selections")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + invokingToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"membershipId\":\"" + secondContext.membershipId() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-Nexa-Refresh-Token"))
                    .andReturn();
            var selectedBody = json(selected);
            assertThat(selectedBody.at("/session/membershipId").asText()).isEqualTo(secondContext.membershipId());
            assertThat(selected.getResponse().getContentAsString()).doesNotContain("refreshToken");
            String replacementCookieHeader = selected.getResponse().getHeader(HttpHeaders.SET_COOKIE);
            assertThat(replacementCookieHeader)
                    .contains("NEXA_PLATFORM_REFRESH=")
                    .contains("HttpOnly")
                    .contains("SameSite=Strict")
                    .contains("Path=/api/v1/authentication");
            String replacementRefreshCookie = cookieValue(selected, "NEXA_PLATFORM_REFRESH");
            assertThat(replacementRefreshCookie).isNotEqualTo(invokingRefreshCookie);

            String replacementToken = selectedBody.get("accessToken").asText();
            String replacementSession = sessionId(replacementToken);
            String replacementFamily = jdbc.queryForObject("select family_id::text from iam.refresh_session where id=?",
                    String.class, java.util.UUID.fromString(replacementSession));
            assertThat(replacementFamily).isNotEqualTo(invokingFamily).isNotEqualTo(unrelatedFamily);
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + invokingToken))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + unrelatedToken))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + replacementToken))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where family_id=? and family_revoked_at is not null",
                    Integer.class, java.util.UUID.fromString(invokingFamily))).isGreaterThan(0);
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where family_id=? and family_revoked_at is not null",
                    Integer.class, java.util.UUID.fromString(unrelatedFamily))).isZero();
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where family_id=? and family_revoked_at is not null",
                    Integer.class, java.util.UUID.fromString(replacementFamily))).isZero();

            mockMvc.perform(post("/api/v1/authentication/refresh")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .cookie(new Cookie("NEXA_PLATFORM_REFRESH", invokingRefreshCookie)))
                    .andExpect(status().isUnauthorized());
            MvcResult rotated = mockMvc.perform(post("/api/v1/authentication/refresh")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header("X-Nexa-Surface", "PLATFORM")
                            .cookie(new Cookie("NEXA_PLATFORM_REFRESH", replacementRefreshCookie)))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-Nexa-Refresh-Token"))
                    .andReturn();
            String rotatedCookieHeader = rotated.getResponse().getHeader(HttpHeaders.SET_COOKIE);
            assertThat(rotatedCookieHeader)
                    .contains("NEXA_PLATFORM_REFRESH=")
                    .contains("HttpOnly")
                    .contains("SameSite=Strict")
                    .contains("Path=/api/v1/authentication");
            String rotatedRefreshCookie = cookieValue(rotated, "NEXA_PLATFORM_REFRESH");
            assertThat(rotatedRefreshCookie).isNotEqualTo(replacementRefreshCookie);
            String rotatedAccessToken = json(rotated).get("accessToken").asText();
            mockMvc.perform(get("/api/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedAccessToken))
                    .andExpect(status().isOk());
        } finally {
            deleteSecondWorkspace(secondContext);
        }
    }

    @Test void concurrentBearerContextSwitchesRevokeOneFamilyAndCreateOneSession() throws Exception {
        CreatedContext secondContext = createSecondWorkspaceMembership(SALES_EMAIL);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String invokingToken = accessToken(SALES_EMAIL, "PLATFORM");
            String invokingSession = sessionId(invokingToken);
            String invokingFamily = jdbc.queryForObject("select family_id::text from iam.refresh_session where id=?",
                    String.class, java.util.UUID.fromString(invokingSession));
            Integer previousSessions = jdbc.queryForObject("select count(*) from iam.refresh_session where membership_id=?",
                    Integer.class, java.util.UUID.fromString(secondContext.membershipId()));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var switchContext = (java.util.concurrent.Callable<MvcResult>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent switch gate timed out");
                return mockMvc.perform(post("/api/v1/me/access-context-selections")
                                .header("X-Nexa-Client", "NATIVE")
                                .header("X-Nexa-Surface", "PLATFORM")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + invokingToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"membershipId\":\"" + secondContext.membershipId() + "\"}"))
                        .andReturn();
            };
            var first = executor.submit(switchContext);
            var second = executor.submit(switchContext);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .as("Concurrent selection outcomes by status and safe problem code: %s",
                            List.of(safeClassification(firstResult), safeClassification(secondResult)))
                    .containsExactlyInAnyOrder(200, 401);
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where membership_id=?",
                    Integer.class, java.util.UUID.fromString(secondContext.membershipId()))).isEqualTo(previousSessions + 1);
            assertThat(jdbc.queryForObject("select count(*) from iam.refresh_session where family_id=? and family_revoked_at is not null",
                    Integer.class, java.util.UUID.fromString(invokingFamily))).isGreaterThan(0);
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

    private String sessionId(String accessToken) throws Exception {
        String payload = accessToken.split("\\.")[1];
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(payload);
        return tools.jackson.databind.json.JsonMapper.shared().readTree(decoded).get("sid").asText();
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(result.getResponse().getContentAsString());
    }

    private String safeClassification(MvcResult result) {
        int status = result.getResponse().getStatus();
        if (status < 400) return status + ":SUCCESS";
        try {
            var problem = json(result);
            var code = problem.get("code");
            if (code == null && problem.get("properties") != null) code = problem.get("properties").get("code");
            if (code != null) return status + ":" + code.asText();
            return status + ":" + problem.path("type").asText("UNKNOWN_PROBLEM_TYPE");
        } catch (Exception ignored) {
            return status + ":UNPARSEABLE_PROBLEM";
        }
    }

    private String cookieValue(MvcResult result, String cookieName) {
        String cookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        if (cookieHeader == null) throw new AssertionError("Expected a refresh cookie response");
        String prefix = cookieName + "=";
        int valueStart = cookieHeader.indexOf(prefix);
        if (valueStart < 0) throw new AssertionError("Expected the surface refresh cookie response");
        valueStart += prefix.length();
        int valueEnd = cookieHeader.indexOf(';', valueStart);
        if (valueEnd < 0) valueEnd = cookieHeader.length();
        return cookieHeader.substring(valueStart, valueEnd);
    }

    private String signInPayload() {
        return "{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"workspaceSlug\":\"" + WORKSPACE_SLUG + "\",\"surface\":\"PLATFORM\"}";
    }
}
