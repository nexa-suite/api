package com.nexa.api.tenantaccessgovernance.iam.presentation.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authentication")
@Tag(name = "Authentication")
public class CsrfTokenController {
	@GetMapping("/csrf")
	@Operation(summary = "Get a CSRF token for browser session requests",
			description = "Returns a request token for browser sign-in, refresh and sign-out. Native clients use the explicit native session transport instead.")
	public CsrfTokenResponse csrfToken(@Parameter(hidden = true) CsrfToken csrfToken, HttpServletResponse response) {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		return new CsrfTokenResponse(csrfToken.getToken());
	}

	public record CsrfTokenResponse(String token) { }
}
