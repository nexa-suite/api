package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.shared.presentation.error.ApiErrorCode;
import com.nexa.api.shared.presentation.error.ApiProblemDetailFactory;
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

final class CookieOriginGuardFilter extends OncePerRequestFilter {
	private final ObjectMapper objectMapper;
	private final Set<String> allowedOrigins;

	CookieOriginGuardFilter(ObjectMapper objectMapper, Set<String> allowedOrigins) {
		this.objectMapper = objectMapper;
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (request.getMethod().equalsIgnoreCase("POST") && request.getRequestURI().startsWith("/api/v1/authentication/")) {
			String origin = request.getHeader("Origin");
			if (origin == null || !allowedOrigins.contains(origin)) {
				var problem = ApiProblemDetailFactory.create(HttpStatus.FORBIDDEN, ApiErrorCode.ORIGIN_NOT_ALLOWED,
						"Request origin is not allowed", request);
				response.setStatus(HttpStatus.FORBIDDEN.value());
				response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				objectMapper.writeValue(response.getWriter(), problem);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}
}
