package com.nexa.api.iam.presentation.rest;

import com.nexa.api.iam.application.exception.InvalidRefreshTokenException;
import com.nexa.api.iam.application.model.AuthenticationResult;
import com.nexa.api.iam.application.model.CurrentSession;
import com.nexa.api.iam.application.model.CurrentSessionQuery;
import com.nexa.api.iam.application.model.LoginIdentifier;
import com.nexa.api.iam.application.model.RefreshSessionCommand;
import com.nexa.api.iam.application.model.SignInCommand;
import com.nexa.api.iam.application.model.SignOutCommand;
import com.nexa.api.iam.application.port.in.CurrentSessionUseCase;
import com.nexa.api.iam.application.port.in.RefreshSessionUseCase;
import com.nexa.api.iam.application.port.in.SignInUseCase;
import com.nexa.api.iam.application.port.in.SignOutUseCase;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Clock;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Authentication")
@Profile("!test")
public class AuthenticationController {
	private static final String PLATFORM_COOKIE = "NEXA_PLATFORM_REFRESH";
	private static final String PORTAL_COOKIE = "NEXA_PORTAL_REFRESH";
	private final SignInUseCase signIn;
	private final RefreshSessionUseCase refresh;
	private final SignOutUseCase signOut;
	private final CurrentSessionUseCase currentSession;
	private final boolean secureCookie;
	private final Clock clock;

	public AuthenticationController(SignInUseCase signIn, RefreshSessionUseCase refresh, SignOutUseCase signOut,
			CurrentSessionUseCase currentSession, @Value("${nexa.security.refresh-cookie-secure:true}") boolean configuredSecureCookie,
			Environment environment, Clock clock) {
		this.signIn = signIn;
		this.refresh = refresh;
		this.signOut = signOut;
		this.currentSession = currentSession;
		this.secureCookie = environment.acceptsProfiles(Profiles.of("local")) && !configuredSecureCookie
				? false : true;
		this.clock = clock;
	}

	@PostMapping("/authentication/sign-in")
	@Operation(summary = "Authenticate a user and establish a refresh session")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Authenticated session returned"),
			@ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
			@ApiResponse(responseCode = "401", description = "Authentication failed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Origin not allowed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))})
	public AuthenticationResponse signIn(@Valid @RequestBody SignInRequest request, HttpServletResponse response) {
		AuthenticationResult result = signIn.signIn(new SignInCommand(new LoginIdentifier(request.identifier()), request.password(),
				request.workspaceSlug(), request.surface()));
		writeRefreshCookie(response, result.surface(), result.refreshToken(), result.refreshTokenExpiresAt());
		return AuthenticationResponse.from(result);
	}

	@PostMapping("/authentication/refresh")
	@Operation(summary = "Rotate the current surface refresh session")
	@SecurityRequirement(name = "refreshCookie")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Rotated authenticated session returned"),
			@ApiResponse(responseCode = "401", description = "Refresh session invalid", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
			@ApiResponse(responseCode = "403", description = "Origin not allowed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))})
	public AuthenticationResponse refresh(@Parameter(description = "PLATFORM or PORTAL", required = true)
			@RequestHeader(name = "X-Nexa-Surface") String surface,
			@CookieValue(name = PLATFORM_COOKIE, required = false) String platformRefresh,
			@CookieValue(name = PORTAL_COOKIE, required = false) String portalRefresh, HttpServletResponse response) {
		ClientSurface requestedSurface = parseSurface(surface);
		String refreshToken = requestedSurface == ClientSurface.PLATFORM ? platformRefresh : portalRefresh;
		if (refreshToken == null || refreshToken.isBlank()) throw new InvalidRefreshTokenException();
		AuthenticationResult result = refresh.refresh(new RefreshSessionCommand(refreshToken, requestedSurface));
		writeRefreshCookie(response, result.surface(), result.refreshToken(), result.refreshTokenExpiresAt());
		return AuthenticationResponse.from(result);
	}

	@PostMapping("/authentication/sign-out")
	@Operation(summary = "Revoke the current session")
	@ApiResponses({@ApiResponse(responseCode = "204", description = "Session revoked and cookie cleared"),
			@ApiResponse(responseCode = "403", description = "Origin not allowed", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))})
	public ResponseEntity<Void> signOut(@RequestHeader(name = "Authorization", required = false) String authorization,
			@RequestHeader(name = "X-Nexa-Surface", required = false) String surface, HttpServletResponse response) {
		if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			signOut.signOut(new SignOutCommand(authorization.substring(7)));
		}
		if (surface != null && !surface.isBlank()) clearRefreshCookie(response, parseSurface(surface));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/session")
	@Operation(summary = "Get the current authenticated session")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Current session returned"),
			@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))})
	public SessionResponse session(@RequestHeader("Authorization") String authorization) {
		if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw new IllegalArgumentException("Bearer token is required");
		return SessionResponse.from(currentSession.currentSession(new CurrentSessionQuery(authorization.substring(7))));
	}

	private void writeRefreshCookie(HttpServletResponse response, ClientSurface surface, String value, java.time.Instant expiresAt) {
		String name = cookieName(surface);
		ResponseCookie cookie = ResponseCookie.from(name, value).httpOnly(true).secure(secureCookie).sameSite("Strict")
				.path("/api/v1/authentication").maxAge(Duration.between(clock.instant(), expiresAt)).build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	private void clearRefreshCookie(HttpServletResponse response, ClientSurface surface) {
		response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName(surface), "").httpOnly(true).secure(secureCookie)
				.sameSite("Strict").path("/api/v1/authentication").maxAge(Duration.ZERO).build().toString());
	}

	private static String cookieName(ClientSurface surface) {
		return surface == ClientSurface.PLATFORM ? PLATFORM_COOKIE : PORTAL_COOKIE;
	}

	private static ClientSurface parseSurface(String value) {
		try { return ClientSurface.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (RuntimeException exception) { throw new IllegalArgumentException("Surface is invalid"); }
	}

	public record SignInRequest(@NotBlank String identifier, @NotBlank String password, @NotBlank String workspaceSlug,
			@NotNull ClientSurface surface) {}

	public record AuthenticationResponse(String accessToken, String tokenType, long expiresIn, SessionContext session) {
		static AuthenticationResponse from(AuthenticationResult result) {
			return new AuthenticationResponse(result.accessToken(), "Bearer",
					Duration.between(result.issuedAt(), result.accessTokenExpiresAt()).toSeconds(),
					new SessionContext(result.userAccountId().value(), result.displayName(), result.email().value(), result.preferredLanguage(),
							result.tenantId(), result.tenantSlug(), result.workspaceId(), result.workspaceSlug(), result.membershipId(),
							result.role(), result.permissions(), result.surface().name()));
		}
	}

	public record SessionResponse(SessionUser user, TenantContext tenant, WorkspaceContext workspace,
			MembershipContext membership, String surface) {
		static SessionResponse from(CurrentSession session) {
			return new SessionResponse(
					new SessionUser(session.userAccountId().value(), session.displayName(), session.email().value(), session.preferredLanguage()),
					new TenantContext(session.tenantId(), session.tenantSlug()),
					new WorkspaceContext(session.workspaceId(), session.workspaceSlug()),
					new MembershipContext(session.membershipId(), session.role(), session.permissions()),
					session.surface().name());
		}
	}

	public record SessionContext(String userId, String displayName, String email, String preferredLanguage,
			String tenantId, String tenantSlug, String workspaceId, String workspaceSlug, String membershipId,
			String role, java.util.Set<String> permissions, String surface) {}

	public record SessionUser(String userId, String displayName, String email, String preferredLanguage) {}
	public record TenantContext(String tenantId, String tenantSlug) {}
	public record WorkspaceContext(String workspaceId, String workspaceSlug) {}
	public record MembershipContext(String membershipId, String role, java.util.Set<String> permissions) {}
}
