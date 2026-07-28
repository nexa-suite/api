package com.nexa.api.shared.presentation.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {
	public static final String HEADER_NAME = "X-Correlation-ID";
	public static final String ATTRIBUTE_NAME = CorrelationIdFilter.class.getName() + ".correlationId";
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String correlationId = validOrGenerated(request.getHeader(HEADER_NAME));
		request.setAttribute(ATTRIBUTE_NAME, correlationId);
		response.setHeader(HEADER_NAME, correlationId);
		MDC.put(HEADER_NAME, correlationId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(HEADER_NAME);
		}
	}

	public static boolean isValid(String value) {
		return value != null && SAFE_ID.matcher(value).matches();
	}

	private static String validOrGenerated(String value) {
		return isValid(value) ? value : UUID.randomUUID().toString();
	}
}
