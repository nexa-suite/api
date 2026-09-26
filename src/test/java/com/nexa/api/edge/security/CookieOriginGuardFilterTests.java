package com.nexa.api.edge.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;
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
	void allowsBrowserBearerContextAuthorityButKeepsTicketsNativeAndSelectionOriginBound() throws Exception {
		assertBearerAllowed("/api/v1/me/access-contexts", "GET", ALLOWED_ORIGIN);
		assertBearerAllowed("/api/v1/me/access-contexts", "GET", null);
		assertBearerAllowed("/api/v1/me/access-context-selections", "POST", ALLOWED_ORIGIN);
		assertBearerRejected("/api/v1/me/access-context-selections", null, "ORIGIN_NOT_ALLOWED");
		assertBearerRejected("/api/v1/me/access-context-selections", "https://evil.example", "ORIGIN_NOT_ALLOWED");
		assertBrowserTicketRejected("/api/v1/me/access-contexts", "GET", ALLOWED_ORIGIN);
		assertBrowserTicketRejected("/api/v1/me/access-context-selections", "POST", ALLOWED_ORIGIN);
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

	private static void assertBearerAllowed(String path, String method, String origin) throws Exception {
		MockHttpServletRequest request = request(method, path, origin);
		request.addHeader("Authorization", "Bearer context-authority");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter().doFilter(request, response, chain);
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(request);
	}

	private static void assertBearerRejected(String path, String origin, String expectedCode) throws Exception {
		MockHttpServletRequest request = request("POST", path, origin);
		request.addHeader("Authorization", "Bearer context-authority");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter().doFilter(request, response, chain);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(problemCode(response)).isEqualTo(expectedCode);
		assertThat(chain.getRequest()).isNull();
	}

	private static void assertBrowserTicketRejected(String path, String method, String origin) throws Exception {
		MockHttpServletRequest request = request(method, path, origin);
		request.addHeader("X-Nexa-Context-Ticket", "opaque-ticket");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter().doFilter(request, response, chain);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(problemCode(response)).isEqualTo("NATIVE_CLIENT_REQUIRED");
		assertThat(chain.getRequest()).isNull();
	}

	private static String problemCode(MockHttpServletResponse response) throws Exception {
		var problem = JsonMapper.shared().readTree(response.getContentAsString());
		var code = problem.get("code");
		if (code == null && problem.get("properties") != null) code = problem.get("properties").get("code");
		if (code != null) return code.asText();
		String type = problem.get("type").asText();
		return type.substring(type.lastIndexOf(':') + 1).replace('-', '_').toUpperCase(Locale.ROOT);
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
