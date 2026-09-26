package com.nexa.api.edge.security;

import com.nexa.api.tenantaccessgovernance.iam.presentation.transport.AuthenticationTransport;
import com.nexa.api.edge.problem.ApiErrorCode;
import com.nexa.api.edge.problem.ApiProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

public final class CookieOriginGuardFilter extends OncePerRequestFilter {
	public static final String NATIVE_CLIENT_HEADER = AuthenticationTransport.NATIVE_CLIENT_HEADER;
	private final ObjectMapper objectMapper;
	private final Set<String> allowedOrigins;

	CookieOriginGuardFilter(ObjectMapper objectMapper, Set<String> allowedOrigins) {
		this.objectMapper = objectMapper;
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (AuthenticationTransport.isAccessContextAuthorityEndpoint(request.getRequestURI())) {
			boolean hasTicket = hasValue(request.getHeader(AuthenticationTransport.ACCESS_CONTEXT_TICKET_HEADER));
			boolean hasBearer = isBearerAuthorization(request.getHeader("Authorization"));
			if (hasTicket && hasBearer) {
				writeProblem(response, HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
						"Exactly one access context authority is required", request);
				return;
			}
			if (hasTicket && !isNativeSessionTransport(request)) {
				writeProblem(response, HttpStatus.FORBIDDEN, ApiErrorCode.NATIVE_CLIENT_REQUIRED,
						"This operation requires the native client transport", request);
				return;
			}
		}
		if (AuthenticationTransport.requiresNativeAccessContextTransport(request.getRequestURI())
				&& !isNativeSessionTransport(request)) {
			writeProblem(response, HttpStatus.FORBIDDEN, ApiErrorCode.NATIVE_CLIENT_REQUIRED,
					"This operation requires the native client transport", request);
			return;
		}
		if (request.getMethod().equalsIgnoreCase("POST")
				&& (request.getRequestURI().startsWith("/api/v1/authentication/")
					|| request.getRequestURI().startsWith("/api/v1/auth/")
					|| "/api/v1/me/access-context-selections".equals(request.getRequestURI()))) {
			String origin = request.getHeader("Origin");
			if ((origin == null || !allowedOrigins.contains(origin)) && !isNativeSessionTransport(request)) {
				writeProblem(response, HttpStatus.FORBIDDEN, ApiErrorCode.ORIGIN_NOT_ALLOWED, "Request origin is not allowed", request);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private void writeProblem(HttpServletResponse response, HttpStatus status, ApiErrorCode code, String detail, HttpServletRequest request)
			throws IOException {
		var problem = ApiProblemDetailFactory.create(status, code, detail, request);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), problem);
	}

	public static boolean isNativeSessionTransport(HttpServletRequest request) {
		return AuthenticationTransport.isNativeSessionTransport(request.getHeader("Origin"),
				request.getHeader(NATIVE_CLIENT_HEADER), request.getRequestURI());
	}

	private static boolean hasValue(String value) { return value != null && !value.isBlank(); }

	private static boolean isBearerAuthorization(String value) {
		return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7) && !value.substring(7).isBlank();
	}
}
