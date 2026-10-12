package com.nexa.api.edge.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InternalOperatorCorsConfigurationTests {
	@Test
	void internalOperatorCredentialsAreNotAllowedAsBrowserCorsHeaders() {
		var environment = new MockEnvironment()
				.withProperty("nexa.security.allowed-origins", "https://platform.nexa.test");
		var request = new MockHttpServletRequest("OPTIONS", "/api/v1/internal/support/requests");
		var cors = new ApiSecurityConfiguration().corsConfigurationSource(environment).getCorsConfiguration(request);

		assertThat(cors).isNotNull();
		assertThat(cors.checkOrigin("https://platform.nexa.test")).isEqualTo("https://platform.nexa.test");
		assertThat(cors.checkHeaders(List.of("X-Nexa-Internal-Operator-Id", "X-Nexa-Internal-Operator-Token")))
				.isNull();
		assertThat(cors.checkHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key")))
				.containsExactly("Authorization", "Content-Type", "Idempotency-Key");
	}
}
