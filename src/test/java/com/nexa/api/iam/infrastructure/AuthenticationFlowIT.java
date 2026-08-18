package com.nexa.api.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

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

    @Test void refreshCookieRequiresAllowedOriginAndKeepsBoundaryAttributes() throws Exception {
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
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/authentication");
        String cookiePair = setCookie.substring(0, setCookie.indexOf(';'));

        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header(HttpHeaders.COOKIE, cookiePair))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header(HttpHeaders.COOKIE, cookiePair))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header("X-Nexa-Surface", "PORTAL")
                        .header(HttpHeaders.COOKIE, cookiePair))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/authentication/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header("X-Nexa-Surface", "PLATFORM")
                        .header(HttpHeaders.COOKIE, cookiePair))
                .andExpect(status().isOk());
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

    private String signInPayload() {
        return "{\"identifier\":\"" + SALES_EMAIL + "\",\"password\":\"" + TEST_PASSWORD
                + "\",\"workspaceSlug\":\"" + WORKSPACE_SLUG + "\",\"surface\":\"PLATFORM\"}";
    }
}
