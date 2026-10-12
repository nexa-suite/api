package com.nexa.api.edge.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityBoundaryTests {
	private static final String ALLOWED_ORIGIN = "http://localhost:4200";
	private static final String CSRF_ENDPOINT = "/api/v1/authentication/csrf";
	private static final String REFRESH_ENDPOINT = "/api/v1/authentication/refresh";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void anonymousCatalogAccessReturnsUnauthorizedProblemDetails() throws Exception {
		mockMvc.perform(get("/api/v1/catalog-items"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	@WithMockUser(username = "mock-user")
	void authenticatedCatalogAccessRemainsDeniedByDefault() throws Exception {
		mockMvc.perform(get("/api/v1/catalog-items"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void swaggerIsUnauthorizedByDefaultInNonLocalProfile() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void csrfBootstrapReturnsNonCacheableTokenAndHttpOnlyCookie() throws Exception {
		MvcResult result = mockMvc.perform(get(CSRF_ENDPOINT))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andReturn();

		var csrfCookie = result.getResponse().getCookie("NEXA_CSRF");
		assertThat(csrfCookie).isNotNull();
		assertThat(csrfCookie.isHttpOnly()).isTrue();
		assertThat(csrfCookie.getSecure()).isTrue();
		assertThat(csrfCookie.getPath()).isEqualTo("/api/v1/authentication");
		assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Strict");
		assertThat(tools.jackson.databind.json.JsonMapper.shared()
				.readTree(result.getResponse().getContentAsString()).get("token").asText()).isNotBlank();
	}

	@Test
	void browserSessionPostsRequireCsrfToken() throws Exception {
		for (String path : List.of(
				"/api/v1/authentication/sign-in",
				REFRESH_ENDPOINT,
				"/api/v1/authentication/sign-out")) {
			mockMvc.perform(post(path).header("Origin", ALLOWED_ORIGIN))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void nativeMarkerWithBrowserOriginDoesNotBypassCsrf() throws Exception {
		mockMvc.perform(post(REFRESH_ENDPOINT)
				.header("X-Nexa-Client", "NATIVE")
				.header("X-Nexa-Surface", "PLATFORM")
				.header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isForbidden());
	}

	@Test
	void browserRefreshAcceptsTokenFromCsrfBootstrap() throws Exception {
		MvcResult result = mockMvc.perform(get(CSRF_ENDPOINT)).andExpect(status().isOk()).andReturn();
		String token = tools.jackson.databind.json.JsonMapper.shared()
				.readTree(result.getResponse().getContentAsString()).get("token").asText();
		var csrfCookie = result.getResponse().getCookie("NEXA_CSRF");
		assertThat(csrfCookie).isNotNull();

		// AuthenticationController is absent in the test profile; 404 proves CSRF accepted the browser request.
		mockMvc.perform(post(REFRESH_ENDPOINT)
				.cookie(csrfCookie)
				.header("X-Nexa-CSRF", token)
				.header("X-Nexa-Surface", "PLATFORM")
				.header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isNotFound());
	}

	@Test
	void explicitNativeRefreshDoesNotRequireBrowserCsrfToken() throws Exception {
		// AuthenticationController is absent in the test profile; 404 proves the native request bypassed browser CSRF.
		mockMvc.perform(post(REFRESH_ENDPOINT)
				.header("X-Nexa-Client", "NATIVE")
				.header("X-Nexa-Surface", "PLATFORM")
				.header("X-Nexa-Refresh-Token", "invalid"))
			.andExpect(status().isNotFound());
	}
}
