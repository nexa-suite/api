package com.nexa.api.iam.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IamSecurityUseCase {
	record Actor(UUID userId, UUID sessionId, String surface, UUID tenantId, UUID workspaceId,
			String correlationId, String traceId) {}
	record Profile(UUID userId, String email, String displayName, String phone, String preferredLanguage,
			String timezone, long version) {}
	record ProfilePatch(String displayName, String phone, String preferredLanguage, String timezone, long version) {}
	record Session(UUID id, String surface, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
			boolean current, String deviceLabel, String coarseIp) {}
	record Registration(String id, String status, Instant submittedAt) {}
	record RegistrationRequest(String legalName, String displayName, String businessIdentifier,
			String operationCategory, String storageSiteName, String storageSiteAddress, String founderEmail,
			String founderDisplayName, String workspaceName, String workspaceSlug, String referencePlan,
			String termsVersion, boolean termsAccepted) {}
	record Activation(String registrationId, String status, UUID tenantId, UUID workspaceId, UUID founderUserId,
			java.util.Set<String> roles) {}

	Profile profile(Actor actor);
	Profile updateProfile(Actor actor, ProfilePatch patch);
	void changePassword(Actor actor, String currentPassword, String newPassword);
	List<Session> sessions(Actor actor);
	void revokeSession(Actor actor, UUID sessionId);
	void revokeOtherSessions(Actor actor);
	String requestPasswordReset(String email, String surface, String correlationId, String traceId);
	void resetPassword(String token, String newPassword, String correlationId, String traceId);
	Registration submitRegistration(RegistrationRequest request, String correlationId, String traceId);
	Registration registration(UUID registrationId);
	Activation activate(UUID registrationId, String operatorToken, String correlationId, String traceId);
	Registration reject(UUID registrationId, String operatorToken, String reason, String correlationId, String traceId);
}
