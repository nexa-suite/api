package com.nexa.api.tenantmanagement.domain.model.invitation;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class OrganizationInvitation {
	private final UUID id;
	private final TenantId tenantId;
	private final WorkspaceId workspaceId;
	private final String email;
	private final String displayName;
	private final InvitationTokenHash tokenHash;
	private final Set<MembershipRole> roles;
	private final InvitationExpiry expiry;
	private final MembershipId creator;
	private InvitationStatus status;

	private OrganizationInvitation(UUID id, TenantId tenantId, WorkspaceId workspaceId, String email, String displayName,
			InvitationTokenHash tokenHash, Set<MembershipRole> roles, InvitationExpiry expiry, MembershipId creator, InvitationStatus status) {
		this.id = id; this.tenantId = tenantId; this.workspaceId = workspaceId; this.email = email; this.displayName = displayName;
		this.tokenHash = tokenHash; this.roles = Set.copyOf(roles); this.expiry = expiry; this.creator = creator; this.status = status;
		if (roles.isEmpty() || roles.contains(MembershipRole.BUYER)) throw new TenantManagementInvariantViolation("Invitation roles are invalid");
	}

	public static OrganizationInvitation pending(UUID id, TenantId tenantId, WorkspaceId workspaceId, String email, String displayName,
			InvitationTokenHash tokenHash, Set<MembershipRole> roles, InvitationExpiry expiry, MembershipId creator) {
		if (email == null || !email.contains("@") || displayName == null || displayName.isBlank()) throw new TenantManagementInvariantViolation("Invitation identity is invalid");
		return new OrganizationInvitation(id, tenantId, workspaceId, email.strip().toLowerCase(java.util.Locale.ROOT), displayName.strip(), tokenHash, roles, expiry, creator, InvitationStatus.PENDING);
	}

	public static OrganizationInvitation restore(UUID id, TenantId tenantId, WorkspaceId workspaceId, String email, String displayName,
			InvitationTokenHash tokenHash, Set<MembershipRole> roles, InvitationExpiry expiry, MembershipId creator, InvitationStatus status) {
		return new OrganizationInvitation(id, tenantId, workspaceId, email, displayName, tokenHash, roles, expiry, creator, status);
	}

	public void accept(Clock clock) { ensurePending(clock); status = InvitationStatus.ACCEPTED; }
	public void revoke(Clock clock) { ensurePending(clock); status = InvitationStatus.REVOKED; }
	public void expire(Clock clock) { if (status == InvitationStatus.PENDING && expiry.hasExpired(clock)) status = InvitationStatus.EXPIRED; }
	private void ensurePending(Clock clock) { expire(clock); if (status != InvitationStatus.PENDING) throw new TenantManagementInvariantViolation("Invitation is no longer pending"); }

	public UUID id() { return id; }
	public TenantId tenantId() { return tenantId; }
	public WorkspaceId workspaceId() { return workspaceId; }
	public String email() { return email; }
	public String displayName() { return displayName; }
	public InvitationTokenHash tokenHash() { return tokenHash; }
	public Set<MembershipRole> roles() { return roles; }
	public InvitationExpiry expiry() { return expiry; }
	public MembershipId creator() { return creator; }
	public InvitationStatus status() { return status; }
}
