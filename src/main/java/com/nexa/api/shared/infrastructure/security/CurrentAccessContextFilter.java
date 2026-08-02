package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.iam.application.port.in.ValidateAccessSessionUseCase;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** Revalidates the JWT access scope against the active database membership on every bearer request. */
final class CurrentAccessContextFilter extends OncePerRequestFilter {
	static final String ACCESS_CONTEXT_ATTRIBUTE = CurrentAccessContext.class.getName();

	private final ResolveCurrentAccessContextUseCase accessContext;
	private final ValidateAccessSessionUseCase accessSession;
	private final AuthenticationEntryPoint authenticationEntryPoint;
	private final AuthenticationEntryPoint accessTokenInvalidEntryPoint;
	private final AccessDeniedHandler accessDeniedHandler;

	CurrentAccessContextFilter(ResolveCurrentAccessContextUseCase accessContext,
			ValidateAccessSessionUseCase accessSession, AuthenticationEntryPoint authenticationEntryPoint,
			AuthenticationEntryPoint accessTokenInvalidEntryPoint, AccessDeniedHandler accessDeniedHandler) {
		this.accessContext = Objects.requireNonNull(accessContext, "Access context use case is required");
		this.accessSession = Objects.requireNonNull(accessSession, "Access session use case is required");
		this.authenticationEntryPoint = Objects.requireNonNull(authenticationEntryPoint, "Authentication entry point is required");
		this.accessTokenInvalidEntryPoint = Objects.requireNonNull(accessTokenInvalidEntryPoint, "Access token entry point is required");
		this.accessDeniedHandler = Objects.requireNonNull(accessDeniedHandler, "Access denied handler is required");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
			filterChain.doFilter(request, response);
			return;
		}

		Jwt jwt = jwtAuthentication.getToken();
		Surface surface;
		UserId userId;
		TenantId tenantId;
		WorkspaceId workspaceId;
		String membershipIdClaim;
		String rolesClaim;
		String sessionIdClaim;
		try {
			surface = Surface.valueOf(requiredClaim(jwt, "surface").toUpperCase(java.util.Locale.ROOT));
			userId = new UserId(jwt.getSubject());
			tenantId = new TenantId(requiredClaim(jwt, "tenant_id"));
			workspaceId = new WorkspaceId(requiredClaim(jwt, "workspace_id"));
			membershipIdClaim = requiredClaim(jwt, "membership_id");
			var canonicalRoles = jwt.getClaimAsStringList("roles");
			if (canonicalRoles == null || canonicalRoles.isEmpty()) throw new IllegalArgumentException("Missing JWT claim roles");
			rolesClaim = String.join(",", canonicalRoles);
			sessionIdClaim = requiredClaim(jwt, "sid");
		} catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			authenticationEntryPoint.commence(request, response,
				new BadCredentialsException("The access token claims are invalid", exception));
			return;
		}

		try {
			accessSession.validate(new SessionId(sessionIdClaim), new com.nexa.api.iam.domain.model.useraccount.UserAccountId(jwt.getSubject()),
					com.nexa.api.iam.domain.model.access.ClientSurface.valueOf(surface.name()));
		} catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			accessTokenInvalidEntryPoint.commence(request, response,
					new BadCredentialsException("The access token session is invalid", exception));
			return;
		}

		CurrentAccessContext resolved;
		try {
			resolved = accessContext.resolve(new CurrentAccessRequest(userId, tenantId, workspaceId, surface));
			if (!resolved.membershipId().toString().equals(membershipIdClaim)
					|| !resolved.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")).equals(java.util.Arrays.stream(rolesClaim.split(",")).map(String::trim).sorted().collect(java.util.stream.Collectors.joining(",")))) {
				throw new IllegalStateException("JWT access claims do not match the active workspace membership");
			}
		} catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			accessDeniedHandler.handle(request, response,
				new AccessDeniedException("The active workspace membership is invalid", exception));
			return;
		}

		var authorities = resolved.permissions().stream()
					.map(permission -> new SimpleGrantedAuthority(permission.code()))
					.toList();
		var verifiedAuthentication = new JwtAuthenticationToken(jwt, authorities, jwtAuthentication.getName());
		verifiedAuthentication.setDetails(jwtAuthentication.getDetails());
		SecurityContextHolder.getContext().setAuthentication(verifiedAuthentication);
		request.setAttribute(ACCESS_CONTEXT_ATTRIBUTE, resolved);
		filterChain.doFilter(request, response);
	}

	private static String requiredClaim(Jwt jwt, String name) {
		String value = jwt.getClaimAsString(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing JWT claim " + name);
		return value;
	}
}
