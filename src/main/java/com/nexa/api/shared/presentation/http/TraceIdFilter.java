package com.nexa.api.shared.presentation.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class TraceIdFilter extends OncePerRequestFilter {
	public static final String HEADER_NAME = "X-Trace-ID";
	public static final String ATTRIBUTE_NAME = TraceIdFilter.class.getName() + ".traceId";
	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String value = request.getHeader(HEADER_NAME);
		String traceId = value != null && SAFE.matcher(value).matches() ? value : UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE_NAME, traceId);
		response.setHeader(HEADER_NAME, traceId);
		MDC.put(HEADER_NAME, traceId);
		try { chain.doFilter(request, response); } finally { MDC.remove(HEADER_NAME); }
	}
}
