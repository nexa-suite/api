package com.nexa.api.edge.security;

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

	@Test
	void allowsNativeTransportOnlyOnSessionRoutesWithoutAnOrigin() throws Exception {
		assertNativeAllowed("/api/v1/authentication/sign-in");
		assertNativeAllowed("/api/v1/authentication/refresh");
		assertNativeAllowed("/api/v1/authentication/sign-out");
		assertNativeAllowed("/api/v1/authentication/identity-sign-in");
		assertNativeAllowed("/api/v1/me/access-context-selections");
		assertNativeAllowed("/api/v1/me/access-contexts", "GET");
		assertNativeRejected("/api/v1/auth/workspace-previews");
		assertNativeRejected("/api/v1/authentication/password-resets");
		assertNativeRejected("/api/v1/authentication/identity-sign-in", "https://app.example");
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

	private static void assertNativeAllowed(String path) throws Exception {
		assertNativeAllowed(path, "POST");
	}

	private static void assertNativeAllowed(String path, String method) throws Exception {
		MockHttpServletRequest request = request(method, path, null);
		request.addHeader(CookieOriginGuardFilter.NATIVE_CLIENT_HEADER, "NATIVE");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter().doFilter(request, response, chain);
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(request);
	}

	private static void assertNativeRejected(String path) throws Exception {
		assertNativeRejected(path, null);
	}

	private static void assertNativeRejected(String path, String origin) throws Exception {
		MockHttpServletRequest request = request("POST", path, origin);
		request.addHeader(CookieOriginGuardFilter.NATIVE_CLIENT_HEADER, "NATIVE");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter().doFilter(request, response, chain);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
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
