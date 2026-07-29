package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** Revalidates the JWT access scope against the active database membership on every bearer request. */
final class CurrentAccessContextFilter extends OncePerRequestFilter {
	static final String ACCESS_CONTEXT_ATTRIBUTE = CurrentAccessContext.class.getName();

	private final ResolveCurrentAccessContextUseCase accessContext;
	private final AuthenticationEntryPoint authenticationEntryPoint;

	CurrentAccessContextFilter(ResolveCurrentAccessContextUseCase accessContext,
			AuthenticationEntryPoint authenticationEntryPoint) {
		this.accessContext = Objects.requireNonNull(accessContext, "Access context use case is required");
		this.authenticationEntryPoint = Objects.requireNonNull(authenticationEntryPoint, "Authentication entry point is required");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			Jwt jwt = jwtAuthentication.getToken();
			Surface surface = Surface.valueOf(requiredClaim(jwt, "surface").toUpperCase(java.util.Locale.ROOT));
			CurrentAccessContext resolved = accessContext.resolve(new CurrentAccessRequest(
					new UserId(jwt.getSubject()),
					new TenantId(requiredClaim(jwt, "tenant_id")),
					new WorkspaceId(requiredClaim(jwt, "workspace_id")),
					surface));
			var authorities = resolved.permissions().stream()
					.map(permission -> new SimpleGrantedAuthority(permission.code()))
					.toList();
			var verifiedAuthentication = new JwtAuthenticationToken(jwt, authorities, jwtAuthentication.getName());
			verifiedAuthentication.setDetails(jwtAuthentication.getDetails());
			SecurityContextHolder.getContext().setAuthentication(verifiedAuthentication);
			request.setAttribute(ACCESS_CONTEXT_ATTRIBUTE, resolved);
			filterChain.doFilter(request, response);
		} catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			authenticationEntryPoint.commence(request, response,
					new BadCredentialsException("The active workspace membership is invalid", exception));
		}
	}

	private static String requiredClaim(Jwt jwt, String name) {
		String value = jwt.getClaimAsString(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing JWT claim " + name);
		return value;
	}
}
