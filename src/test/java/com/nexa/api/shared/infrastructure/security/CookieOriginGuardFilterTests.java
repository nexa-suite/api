package com.nexa.api.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CookieOriginGuardFilterTests {
    private static final String ALLOWED_ORIGIN = "http://localhost:4200";

    @Test
    void rejectsMissingAndForeignOriginsForBrowserAuthenticationRequests() throws Exception {
        assertRejected("/api/v1/authentication/sign-in", null);
        assertRejected("/api/v1/authentication/refresh", "https://evil.example");
        assertRejected("/api/v1/auth/workspace-previews", "https://evil.example");
    }

    @Test
    void allowsConfiguredOriginAndLeavesBearerApiCommandsCompatible() throws Exception {
        assertAllowed("/api/v1/authentication/refresh", ALLOWED_ORIGIN);
        assertAllowed("/api/v1/purchase-requests", null);
    }

    private static void assertRejected(String path, String origin) throws Exception {
        MockHttpServletRequest request = request("POST", path, origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    private static void assertAllowed(String path, String origin) throws Exception {
        MockHttpServletRequest request = request("POST", path, origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static MockHttpServletRequest request(String method, String path, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("api", path);
        request.setMethod(method);
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }

    private static CookieOriginGuardFilter filter() {
        return new CookieOriginGuardFilter(JsonMapper.shared(), Set.of(ALLOWED_ORIGIN));
    }
}
