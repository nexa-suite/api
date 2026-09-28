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
		if (AuthenticationTransport.requiresNativeAccessContextTransport(request.getRequestURI())
				&& !isNativeSessionTransport(request)) {
			writeProblem(response, ApiErrorCode.NATIVE_CLIENT_REQUIRED,
					"This operation requires the native client transport", request);
			return;
		}
		if (request.getMethod().equalsIgnoreCase("POST")
				&& (request.getRequestURI().startsWith("/api/v1/authentication/")
					|| request.getRequestURI().startsWith("/api/v1/auth/"))) {
			String origin = request.getHeader("Origin");
			if ((origin == null || !allowedOrigins.contains(origin)) && !isNativeSessionTransport(request)) {
				writeProblem(response, ApiErrorCode.ORIGIN_NOT_ALLOWED, "Request origin is not allowed", request);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private void writeProblem(HttpServletResponse response, ApiErrorCode code, String detail, HttpServletRequest request)
			throws IOException {
		var problem = ApiProblemDetailFactory.create(HttpStatus.FORBIDDEN, code, detail, request);
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), problem);
	}

	public static boolean isNativeSessionTransport(HttpServletRequest request) {
		return AuthenticationTransport.isNativeSessionTransport(request.getHeader("Origin"),
				request.getHeader(NATIVE_CLIENT_HEADER), request.getRequestURI());
	}
}
