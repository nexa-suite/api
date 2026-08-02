package com.nexa.api.iam.presentation.rest;

import com.nexa.api.iam.application.exception.IamSecurityException;
import com.nexa.api.iam.application.model.IamSecurityModels;
import com.nexa.api.iam.application.model.SystemOperatorContext;
import com.nexa.api.iam.application.port.in.ActivateOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.ChangeOwnPasswordCommand;
import com.nexa.api.iam.application.port.in.GetOrganizationRegistrationStatusQuery;
import com.nexa.api.iam.application.port.in.GetOwnProfileQuery;
import com.nexa.api.iam.application.port.in.ListOwnSessionsQuery;
import com.nexa.api.iam.application.port.in.RejectOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.RequestPasswordResetCommand;
import com.nexa.api.iam.application.port.in.ResetPasswordCommand;
import com.nexa.api.iam.application.port.in.RevokeOtherSessionsCommand;
import com.nexa.api.iam.application.port.in.RevokeOwnSessionCommand;
import com.nexa.api.iam.application.port.in.SubmitOrganizationRegistrationCommand;
import com.nexa.api.iam.application.port.in.UpdateOwnProfileCommand;
import com.nexa.api.shared.presentation.http.CorrelationIdFilter;
import com.nexa.api.shared.infrastructure.security.TrustedClientAddressResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1")
@Tag(name = "Identity security")
public final class IamSecurityController {
	private final GetOwnProfileQuery profile;
	private final UpdateOwnProfileCommand updateProfile;
	private final ChangeOwnPasswordCommand changePassword;
	private final ListOwnSessionsQuery sessions;
	private final RevokeOwnSessionCommand revokeSession;
	private final RevokeOtherSessionsCommand revokeOtherSessions;
	private final RequestPasswordResetCommand requestReset;
	private final ResetPasswordCommand resetPassword;
	private final SubmitOrganizationRegistrationCommand submitRegistration;
	private final GetOrganizationRegistrationStatusQuery registrationStatus;
	private final ActivateOrganizationRegistrationCommand activateRegistration;
	private final RejectOrganizationRegistrationCommand rejectRegistration;
	private final TrustedClientAddressResolver clientAddressResolver;

	public IamSecurityController(GetOwnProfileQuery profile, UpdateOwnProfileCommand updateProfile,
			ChangeOwnPasswordCommand changePassword, ListOwnSessionsQuery sessions, RevokeOwnSessionCommand revokeSession,
			RevokeOtherSessionsCommand revokeOtherSessions, RequestPasswordResetCommand requestReset,
			ResetPasswordCommand resetPassword, SubmitOrganizationRegistrationCommand submitRegistration,
			GetOrganizationRegistrationStatusQuery registrationStatus, ActivateOrganizationRegistrationCommand activateRegistration,
			RejectOrganizationRegistrationCommand rejectRegistration, TrustedClientAddressResolver clientAddressResolver) {
		this.profile = profile; this.updateProfile = updateProfile; this.changePassword = changePassword;
		this.sessions = sessions; this.revokeSession = revokeSession; this.revokeOtherSessions = revokeOtherSessions;
		this.requestReset = requestReset; this.resetPassword = resetPassword; this.submitRegistration = submitRegistration;
		this.registrationStatus = registrationStatus; this.activateRegistration = activateRegistration; this.rejectRegistration = rejectRegistration;
		this.clientAddressResolver = clientAddressResolver;
	}

	@GetMapping("/me/profile")
	@Operation(summary = "Read the authenticated user's own profile")
	@SecurityRequirement(name = "bearerAuth")
	public ResponseEntity<ProfileResponse> profile(Authentication authentication, HttpServletRequest request) {
		var value = profile.get(actor(authentication, correlation(request), trace(request)));
		return ResponseEntity.ok().eTag(etag(value.version())).body(toResponse(value));
	}

	@PatchMapping("/me/profile")
	@Operation(summary = "Update the authenticated user's own profile")
	@SecurityRequirement(name = "bearerAuth")
	public ResponseEntity<ProfileResponse> updateProfile(Authentication authentication, HttpServletRequest httpRequest,
			@RequestHeader(value = "If-Match", required = false) String ifMatch, @Valid @RequestBody ProfileRequest request) {
		long version = version(ifMatch);
		var value = updateProfile.update(actor(authentication, correlation(httpRequest), trace(httpRequest)),
				new IamSecurityModels.ProfilePatch(request.displayName(), request.phone(), request.preferredLanguage(), request.timezone(), version));
		return ResponseEntity.ok().eTag(etag(value.version())).body(toResponse(value));
	}

	@PostMapping("/me/password-changes")
	@Operation(summary = "Change the authenticated user's password")
	@SecurityRequirement(name = "bearerAuth")
	public ResponseEntity<Void> changePassword(Authentication authentication, HttpServletRequest httpRequest,
			@Valid @RequestBody PasswordChangeRequest request) {
		changePassword.change(actor(authentication, correlation(httpRequest), trace(httpRequest)), request.currentPassword(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me/sessions")
	@Operation(summary = "List the authenticated user's active sessions")
	@SecurityRequirement(name = "bearerAuth")
	public SessionResponse sessions(Authentication authentication, HttpServletRequest request) {
		var actor = actor(authentication, correlation(request), trace(request));
		return new SessionResponse(sessions.list(actor).stream().map(IamSecurityController::toResponse).toList());
	}

	@DeleteMapping("/me/sessions/{sessionId}")
	@Operation(summary = "Revoke one of the authenticated user's sessions")
	@SecurityRequirement(name = "bearerAuth")
	public ResponseEntity<Void> revokeSession(Authentication authentication, HttpServletRequest request, @PathVariable UUID sessionId) {
		revokeSession.revoke(actor(authentication, correlation(request), trace(request)), sessionId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/me/session-revocations")
	@Operation(summary = "Revoke all sessions except the current one")
	@SecurityRequirement(name = "bearerAuth")
	public ResponseEntity<Void> revokeOtherSessions(Authentication authentication, HttpServletRequest request) {
		revokeOtherSessions.revokeOthers(actor(authentication, correlation(request), trace(request)));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/auth/password-reset-requests")
	@Operation(summary = "Request a generic password reset response")
	public ResetResponse requestReset(HttpServletRequest httpRequest, @Valid @RequestBody ResetRequest request) {
		return new ResetResponse(requestReset.request(request.email(), request.surface(), clientAddressResolver.resolve(httpRequest), correlation(httpRequest), trace(httpRequest)));
	}

	@PostMapping("/auth/password-resets")
	@Operation(summary = "Consume a password reset token")
	public ResponseEntity<Void> resetPassword(HttpServletRequest httpRequest, @Valid @RequestBody ResetPasswordRequest request) {
		resetPassword.reset(request.token(), request.newPassword(), correlation(httpRequest), trace(httpRequest));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/tenant-management/organization-registrations")
	@Operation(summary = "Submit a public organization registration")
	public RegistrationResponse register(HttpServletRequest httpRequest, @Valid @RequestBody RegistrationRequest request) {
		return toResponse(submitRegistration.submit(new IamSecurityModels.RegistrationRequest(request.legalName(), request.displayName(), request.businessIdentifier(),
				request.operationCategory(), request.storageSiteName(), request.storageSiteAddress(), request.founderEmail(), request.founderDisplayName(),
				request.workspaceName(), request.workspaceSlug(), request.referencePlan(), request.termsVersion(), request.termsAccepted()), correlation(httpRequest), trace(httpRequest)));
	}

	@GetMapping("/tenant-management/organization-registrations/{registrationId}")
	@Operation(summary = "Read the public pending organization registration state")
	public RegistrationResponse registration(@PathVariable UUID registrationId, @RequestParam @NotBlank String statusToken) {
		return toResponse(registrationStatus.get(registrationId, statusToken));
	}

	@PostMapping("/internal/organization-registrations/{registrationId}/activation")
	@Operation(summary = "Activate an organization using the system-only boundary")
	public ActivationResponse activate(@PathVariable UUID registrationId, Authentication authentication, HttpServletRequest request) {
		var value = activateRegistration.activate(registrationId, operator(authentication), correlation(request), trace(request));
		return new ActivationResponse(value.registrationId(), value.status(), value.tenantId(), value.workspaceId(), value.founderUserId(), value.roles());
	}

	@PostMapping("/internal/organization-registrations/{registrationId}/rejection")
	@Operation(summary = "Reject an organization using the system-only boundary")
	public RegistrationResponse reject(@PathVariable UUID registrationId, Authentication authentication,
			@Valid @RequestBody RejectionRequest request, HttpServletRequest httpRequest) {
		return toResponse(rejectRegistration.reject(registrationId, operator(authentication), request.reason(), correlation(httpRequest), trace(httpRequest)));
	}

	private static SystemOperatorContext operator(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof SystemOperatorContext value))
			throw new AccessDeniedException("System operator principal is required");
		return value;
	}

	private static IamSecurityModels.Actor actor(Authentication authentication, String correlationId, String traceId) {
		if (!(authentication instanceof JwtAuthenticationToken jwt)) throw new AccessDeniedException("Authenticated JWT is required");
		var token = jwt.getToken();
		try {
			return new IamSecurityModels.Actor(UUID.fromString(token.getSubject()), UUID.fromString(token.getClaimAsString("sid")),
					token.getClaimAsString("surface"), uuid(token.getClaimAsString("tenant_id")), uuid(token.getClaimAsString("workspace_id")),
					correlationId == null ? "unknown" : correlationId, traceId == null ? "unknown" : traceId);
		} catch (RuntimeException exception) { throw new AccessDeniedException("Authenticated scope is invalid", exception); }
	}

	private static UUID uuid(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
	private static long version(String header) {
		if (header == null || header.isBlank()) throw new IamSecurityException("PROFILE_PRECONDITION_REQUIRED");
		try { return Long.parseLong(header.replace("W/", "").replace("\"", "").trim()); }
		catch (NumberFormatException exception) { throw new IamSecurityException("PROFILE_PRECONDITION_REQUIRED"); }
	}
	private static String etag(long version) { return "\"" + version + "\""; }
	private static String correlation(HttpServletRequest request) { Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME); return value == null ? "unknown" : value.toString(); }
	private static String trace(HttpServletRequest request) { String value = request.getHeader("X-Trace-ID"); return value == null || value.isBlank() ? correlation(request) : value; }
	private static ProfileResponse toResponse(IamSecurityModels.Profile value) { return new ProfileResponse(value.userId(), value.email(), value.displayName(), value.phone(), value.preferredLanguage(), value.timezone(), value.version()); }
	private static SessionItem toResponse(IamSecurityModels.Session value) { return new SessionItem(value.id(), value.surface(), value.createdAt(), value.lastSeenAt(), value.expiresAt(), value.current(), value.deviceLabel(), value.coarseIp()); }
	private static RegistrationResponse toResponse(IamSecurityModels.Registration value) { return new RegistrationResponse(value.id(), value.status(), value.submittedAt(), value.statusToken()); }

	public record ProfileResponse(UUID userId, String email, String displayName, String phone, String preferredLanguage, String timezone, long version) {}
	public record ProfileRequest(@NotBlank @Size(max = 160) String displayName, @Size(max = 64) String phone, @NotBlank @Pattern(regexp = "es|en") String preferredLanguage, @NotBlank @Size(max = 64) String timezone) {}
	public record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 128) String newPassword) {}
	public record SessionResponse(List<SessionItem> sessions) {}
	public record SessionItem(UUID sessionId, String surface, Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current, String deviceLabel, String coarseIp) {}
	public record ResetRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Pattern(regexp = "PLATFORM|PORTAL") String surface) {}
	public record ResetResponse(String message) {}
	public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(max = 128) String newPassword) {}
	public record RegistrationRequest(@NotBlank @Size(max = 160) String legalName, @NotBlank @Size(max = 160) String displayName, @Size(max = 80) String businessIdentifier,
			@NotBlank String operationCategory, @NotBlank @Size(max = 160) String storageSiteName, @NotBlank @Size(max = 240) String storageSiteAddress,
			@NotBlank @Email @Size(max = 254) String founderEmail, @NotBlank @Size(max = 160) String founderDisplayName,
			@NotBlank @Size(max = 160) String workspaceName, @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{3,80}") String workspaceSlug,
			@NotBlank @Pattern(regexp = "Starter|Standard|Professional|Enterprise") String referencePlan, @NotBlank @Size(max = 32) String termsVersion, @NotNull Boolean termsAccepted) {}
	public record RegistrationResponse(String registrationId, String status, Instant submittedAt, String statusToken) {}
	public record RejectionRequest(@NotBlank @Size(max = 500) String reason) {}
	public record ActivationResponse(String registrationId, String status, UUID tenantId, UUID workspaceId, UUID founderUserId, Set<String> roles) {}
}
