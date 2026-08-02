package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.shared.application.port.out.SecurityAuditPort;
import com.nexa.api.shared.presentation.error.ApiErrorCode;
import com.nexa.api.shared.presentation.error.ApiProblemDetailFactory;
import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
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
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;

@Component
@Order(-90)
public final class SystemOperatorAuthenticationFilter extends OncePerRequestFilter {
    public static final String PERMISSION = "system:organizations:activate";
    private final ObjectMapper objectMapper;
    private final SecurityAuditPort audit;
    private final byte[] credential;
    private final JdbcTemplate jdbc;

    public SystemOperatorAuthenticationFilter(ObjectMapper objectMapper, ObjectProvider<SecurityAuditPort> audit,
            ObjectProvider<JdbcTemplate> jdbc, @Value("${nexa.security.system-operator-token:}") String configuredCredential) {
        this.objectMapper = objectMapper; this.audit = audit.getIfAvailable(); this.jdbc = jdbc.getIfAvailable();
        this.credential = configuredCredential == null ? new byte[0] : configuredCredential.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isInternalOperatorPath(request)) { chain.doFilter(request, response); return; }
        if (request.getHeader("Origin") != null) { reject(request, response, ApiErrorCode.ORIGIN_NOT_ALLOWED, "Request origin is not allowed"); return; }
		String supplied = request.getHeader("X-Nexa-System-Operator");
		if (credential.length < 32 || supplied == null || !MessageDigest.isEqual(credential, supplied.getBytes(StandardCharsets.UTF_8))) {
			String bucket = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
			if (!registerFailureIfAllowed(bucket)) {
				reject(request, response, ApiErrorCode.SYSTEM_OPERATOR_REQUIRED, "System operator authentication is temporarily limited");
				return;
			}
			appendAudit(new SecurityAuditPort.Event("SYSTEM_OPERATOR_AUTHENTICATION_FAILED", null, null, null, null, "SYSTEM",
					correlation(request), trace(request), Instant.now(), Map.of("bucket", "network")));
			reject(request, response, ApiErrorCode.SYSTEM_OPERATOR_REQUIRED, "System operator authentication is required");
			return;
        }
        var principal = new SystemOperatorContext("system-operator", PERMISSION);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                java.util.List.of(new SimpleGrantedAuthority(PERMISSION))));
        appendAudit(new SecurityAuditPort.Event("SYSTEM_OPERATOR_AUTHENTICATED", null, null, null, null, "SYSTEM",
                correlation(request), trace(request), Instant.now(), Map.of("permission", PERMISSION)));
        chain.doFilter(request, response);
    }

	private boolean registerFailureIfAllowed(String bucket) {
		if (jdbc == null) return true;
		String hash = digest(bucket);
		jdbc.update("insert into iam.system_operator_throttle_bucket (bucket_key_hash,window_started_at,failure_count,updated_at) values (?,current_timestamp,0,current_timestamp) on conflict (bucket_key_hash) do nothing", hash);
		return !jdbc.query("update iam.system_operator_throttle_bucket set failure_count=case when window_started_at <= current_timestamp - interval '1 minute' then 1 else failure_count+1 end,window_started_at=case when window_started_at <= current_timestamp - interval '1 minute' then current_timestamp else window_started_at end,updated_at=current_timestamp where bucket_key_hash=? and (window_started_at <= current_timestamp - interval '1 minute' or failure_count < 10) returning failure_count",
				(rs, row) -> rs.getInt(1), hash).isEmpty();
	}

    private void reject(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code, String detail) throws IOException {
        var problem = ApiProblemDetailFactory.create(HttpStatus.FORBIDDEN, code, detail, request);
        response.setStatus(HttpStatus.FORBIDDEN.value()); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
    private static boolean isInternalOperatorPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.matches("/api/v1/internal/organization-registrations/[^/]+/(activation|rejection)");
    }
    private static String correlation(HttpServletRequest request) { Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME); return value == null ? "unknown" : value.toString(); }
    private static String trace(HttpServletRequest request) { String value = request.getHeader("X-Trace-ID"); return value == null || value.isBlank() ? correlation(request) : value; }
    private void appendAudit(SecurityAuditPort.Event event) { if (audit != null) audit.append(event); }
    private static String digest(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
