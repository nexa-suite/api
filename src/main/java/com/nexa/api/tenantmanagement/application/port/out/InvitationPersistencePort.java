package com.nexa.api.tenantmanagement.application.port.out;

import com.nexa.api.tenantmanagement.application.model.InvitationModels;
import com.nexa.api.tenantmanagement.domain.model.invitation.OrganizationInvitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationPersistencePort {
	Optional<InvitationSnapshot> find(String tenantId, String workspaceId, UUID invitationId);
	List<InvitationSnapshot> findPage(String tenantId, String workspaceId, int page, int pageSize);
	Optional<InvitationSnapshot> findPendingByEmail(String tenantId, String workspaceId, String normalizedEmail);
	Optional<UUID> findIdempotent(String tenantId, String idempotencyKey, String requestHash);
	boolean idempotencyKeyHasDifferentPayload(String tenantId, String idempotencyKey, String requestHash);
	int saveIdempotency(String tenantId, String idempotencyKey, String requestHash, UUID invitationId);
	int create(OrganizationInvitation invitation, Instant createdAt);
	int rotateToken(String tenantId, UUID invitationId, String tokenHash, Instant expiresAt, long expectedVersion);
	int updateStatus(String tenantId, UUID invitationId, String status, Instant changedAt, UUID acceptedUserId, long expectedVersion);
	int expirePending(Instant now, int batchSize);
	Optional<InvitationSnapshot> findForUpdateByTokenHash(String tokenHash);
	Optional<MembershipRecord> findActiveMembershipByEmail(String workspaceId, String normalizedEmail);
	default int activeCompanyOwnerCount(String tenantId) { return 0; }
	default void lockTenant(String tenantId) { }
	Optional<UserRecord> findUserByEmail(String normalizedEmail);
	UUID createUser(String email, String displayName, String passwordHash, Instant now);
	UUID createMembership(String tenantId, String workspaceId, UUID userId, Instant now);
	void assignRoles(UUID membershipId, String tenantId, String workspaceId, java.util.Set<String> roles, Instant now);

	record InvitationSnapshot(OrganizationInvitation invitation, long version, Instant createdAt) { }
	record MembershipRecord(UUID membershipId, UUID userId) { }
	record UserRecord(UUID userId, String email, String status, String passwordHash) { }

	/** Persistence-level signal for a concurrent acceptance of the same workspace membership. */
	final class DuplicateMembershipException extends RuntimeException {
		public DuplicateMembershipException() { super("Workspace membership already exists"); }
	}
}
