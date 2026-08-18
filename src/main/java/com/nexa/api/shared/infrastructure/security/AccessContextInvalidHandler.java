package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.shared.presentation.error.ApiErrorCode;
import com.nexa.api.shared.presentation.error.ApiProblemDetailFactory;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.shared.infrastructure.observability.SecurityMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Returns explicit contract for a valid session with invalid active scope. */
final class AccessContextInvalidHandler implements AccessDeniedHandler {
	private final ObjectMapper objectMapper;
	private final ObjectProvider<SecurityAuditPort> audit;
	private final ObjectProvider<SecurityMetrics> metrics;

	AccessContextInvalidHandler(ObjectMapper objectMapper, ObjectProvider<SecurityAuditPort> audit,
			ObjectProvider<SecurityMetrics> metrics) {
		this.objectMapper = objectMapper;
		this.audit = audit;
		this.metrics = metrics;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		SecurityMetrics metric = metrics.getIfAvailable();
		if (metric != null) metric.increment("authorization.denied");
		SecurityAuditPort port = audit.getIfAvailable();
		if (port != null) {
			var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
			UUID actor = null;
			UUID tenant = null;
			UUID workspace = null;
			if (authentication instanceof JwtAuthenticationToken jwt) {
				actor = uuid(jwt.getToken().getSubject());
				tenant = uuid(jwt.getToken().getClaimAsString("tenant_id"));
				workspace = uuid(jwt.getToken().getClaimAsString("workspace_id"));
			}
			String correlation = request.getAttribute(com.nexa.api.shared.presentation.http.CorrelationIdFilter.ATTRIBUTE_NAME) == null
					? "unknown" : request.getAttribute(com.nexa.api.shared.presentation.http.CorrelationIdFilter.ATTRIBUTE_NAME).toString();
			String trace = request.getHeader("X-Trace-ID");
			port.append(new SecurityAuditPort.Event("SENSITIVE_AUTHORIZATION_DENIED", actor, null, tenant, workspace,
					request.getHeader("X-Nexa-Surface"), correlation, trace == null || trace.isBlank() ? correlation : trace,
					Instant.now(), Map.of("method", request.getMethod(), "path", request.getRequestURI())));
		}
		var problem = ApiProblemDetailFactory.create(HttpStatus.FORBIDDEN, ApiErrorCode.ACCESS_CONTEXT_INVALID,
				"The active workspace membership is invalid", request);
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), problem);
	}

	private static UUID uuid(String value) { try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; } }
}
