package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.shared.presentation.error.ApiErrorCode;
import com.nexa.api.shared.presentation.error.ApiProblemDetailFactory;
import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!test")
@Order(-90)
public final class SystemOperatorAuthenticationFilter extends OncePerRequestFilter {
    public static final String PERMISSION = "system:organizations:activate";
    private final ObjectMapper objectMapper;
    private final SecurityAuditPort audit;
    private final byte[] credential;
    private final ConcurrentHashMap<String, FailureWindow> failures = new ConcurrentHashMap<>();

    public SystemOperatorAuthenticationFilter(ObjectMapper objectMapper, SecurityAuditPort audit,
            @Value("${nexa.security.system-operator-token:}") String configuredCredential) {
        if (configuredCredential == null || configuredCredential.isBlank()) throw new IllegalStateException("System operator credential is required when internal activation is enabled");
        this.objectMapper = objectMapper; this.audit = audit; this.credential = configuredCredential.getBytes(StandardCharsets.UTF_8);
        if (credential.length < 32) throw new IllegalStateException("System operator credential must contain at least 256 bits");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isInternalOperatorPath(request)) { chain.doFilter(request, response); return; }
        if (request.getHeader("Origin") != null) { reject(request, response, ApiErrorCode.ORIGIN_NOT_ALLOWED, "Request origin is not allowed"); return; }
        String bucket = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (isRateLimited(bucket)) { reject(request, response, ApiErrorCode.SYSTEM_OPERATOR_REQUIRED, "System operator authentication is temporarily limited"); return; }
        String supplied = request.getHeader("X-Nexa-System-Operator");
        if (supplied == null || !MessageDigest.isEqual(credential, supplied.getBytes(StandardCharsets.UTF_8))) {
            registerFailure(bucket, request);
            reject(request, response, ApiErrorCode.SYSTEM_OPERATOR_REQUIRED, "System operator authentication is required");
            return;
        }
        failures.remove(bucket);
        var principal = new SystemOperatorContext("system-operator", PERMISSION);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                java.util.List.of(new SimpleGrantedAuthority(PERMISSION))));
        audit.append(new SecurityAuditPort.Event("SYSTEM_OPERATOR_AUTHENTICATION_SUCCEEDED", null, null, null, null, "SYSTEM",
                correlation(request), trace(request), Instant.now(), Map.of("permission", PERMISSION)));
        chain.doFilter(request, response);
    }

    private void registerFailure(String bucket, HttpServletRequest request) {
        failures.compute(bucket, (key, current) -> current == null || current.expiresAt().isBefore(Instant.now())
                ? new FailureWindow(Instant.now().plusSeconds(60), 1)
                : new FailureWindow(current.expiresAt(), current.count() + 1));
        audit.append(new SecurityAuditPort.Event("SYSTEM_OPERATOR_AUTHENTICATION_FAILED", null, null, null, null, "SYSTEM",
                correlation(request), trace(request), Instant.now(), Map.of("bucket", "network")));
    }

    private boolean isRateLimited(String bucket) {
        FailureWindow window = failures.get(bucket);
        return window != null && window.expiresAt().isAfter(Instant.now()) && window.count() >= 10;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code, String detail) throws IOException {
        var problem = ApiProblemDetailFactory.create(HttpStatus.FORBIDDEN, code, detail, request);
        response.setStatus(HttpStatus.FORBIDDEN.value()); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
    private static boolean isInternalOperatorPath(HttpServletRequest request) { return request.getRequestURI().startsWith("/api/v1/internal/organization-registrations/"); }
    private static String correlation(HttpServletRequest request) { Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME); return value == null ? "unknown" : value.toString(); }
    private static String trace(HttpServletRequest request) { String value = request.getHeader("X-Trace-ID"); return value == null || value.isBlank() ? correlation(request) : value; }
    private record FailureWindow(Instant expiresAt, int count) {}
}
