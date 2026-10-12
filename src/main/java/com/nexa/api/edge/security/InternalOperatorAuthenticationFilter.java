package com.nexa.api.edge.security;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalOperatorContext;
import com.nexa.api.edge.problem.ApiErrorCode;
import com.nexa.api.edge.problem.ApiProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authenticates explicit operators using local allowlisted token digests; raw tokens are never retained. */
@Component
public final class InternalOperatorAuthenticationFilter extends OncePerRequestFilter {
	public static final String AUTHORITY = "internal:operator";
	public static final String OPERATOR_ID_HEADER = "X-Nexa-Internal-Operator-Id";
	public static final String OPERATOR_TOKEN_HEADER = "X-Nexa-Internal-Operator-Token";
	private static final int MAX_TOKEN_LENGTH = 4096;
	private final ObjectMapper objectMapper;
	private final Map<UUID, byte[]> operatorTokenDigests;

	public InternalOperatorAuthenticationFilter(ObjectMapper objectMapper,
			@Value("${NEXA_INTERNAL_OPERATOR_ALLOWLIST:}") String configuredAllowlist) {
		this.objectMapper = objectMapper;
		this.operatorTokenDigests = parseAllowlist(configuredAllowlist);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !(path.startsWith("/api/v1/internal/console/")
				|| path.equals("/api/v1/internal/console")
				|| path.startsWith("/api/v1/internal/support/")
				|| path.equals("/api/v1/internal/support"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			chain.doFilter(request, response);
			return;
		}
		UUID operatorId = parseOperatorId(request.getHeader(OPERATOR_ID_HEADER));
		String suppliedToken = request.getHeader(OPERATOR_TOKEN_HEADER);
		byte[] configuredDigest = operatorId == null ? null : operatorTokenDigests.get(operatorId);
		byte[] suppliedDigest = suppliedToken == null || suppliedToken.isBlank()
				|| suppliedToken.length() > MAX_TOKEN_LENGTH ? null : digest(suppliedToken);
		if (configuredDigest == null || suppliedDigest == null
				|| !MessageDigest.isEqual(configuredDigest, suppliedDigest)) {
			reject(request, response);
			return;
		}
		var principal = new InternalOperatorContext(operatorId);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				principal, null, java.util.List.of(new SimpleGrantedAuthority(AUTHORITY))));
		chain.doFilter(request, response);
	}

	private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
		var problem = ApiProblemDetailFactory.create(HttpStatus.FORBIDDEN, ApiErrorCode.SYSTEM_OPERATOR_REQUIRED,
				"An allowlisted internal operator is required", request);
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), problem);
	}

	private static Map<UUID, byte[]> parseAllowlist(String configuration) {
		Map<UUID, byte[]> result = new HashMap<>();
		Set<String> uniqueDigests = new HashSet<>();
		if (configuration == null || configuration.isBlank()) return Map.of();
		for (String entry : configuration.split(",")) {
			String[] parts = entry.trim().split("=", -1);
			if (parts.length != 2
					|| !parts[0].matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
					|| !parts[1].matches("[0-9a-fA-F]{64}")) {
				throw new IllegalStateException("NEXA_INTERNAL_OPERATOR_ALLOWLIST must contain operator UUID and SHA-256 token digest pairs");
			}
			UUID operatorId;
			try {
				operatorId = UUID.fromString(parts[0]);
			} catch (IllegalArgumentException exception) {
				throw new IllegalStateException("NEXA_INTERNAL_OPERATOR_ALLOWLIST contains an invalid operator UUID");
			}
			byte[] tokenDigest = HexFormat.of().parseHex(parts[1]);
			if (result.containsKey(operatorId)) {
				throw new IllegalStateException("NEXA_INTERNAL_OPERATOR_ALLOWLIST contains a duplicate operator UUID");
			}
			if (!uniqueDigests.add(HexFormat.of().formatHex(tokenDigest))) {
				throw new IllegalStateException("NEXA_INTERNAL_OPERATOR_ALLOWLIST contains a duplicate token digest");
			}
			result.put(operatorId, tokenDigest);
		}
		return Map.copyOf(result);
	}

	private static UUID parseOperatorId(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static byte[] digest(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
