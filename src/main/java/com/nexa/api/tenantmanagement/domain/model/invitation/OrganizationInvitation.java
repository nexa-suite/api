package com.nexa.api.tenantmanagement.domain.model.invitation;

import com.nexa.api.tenantmanagement.domain.model.TenantManagementInvariantViolation;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OrganizationInvitation {
	private final UUID id;
	private final TenantId tenantId;
	private final WorkspaceId workspaceId;
	private final String email;
	private final String displayName;
	private InvitationTokenHash tokenHash;
	private final Set<MembershipRole> roles;
	private InvitationExpiry expiry;
	private final MembershipId creator;
	private InvitationStatus status;

	private OrganizationInvitation(UUID id, TenantId tenantId, WorkspaceId workspaceId, String email, String displayName,
			InvitationTokenHash tokenHash, Set<MembershipRole> roles, InvitationExpiry expiry, MembershipId creator, InvitationStatus status) {
		this.id = required(id, "Invitation id");
		this.tenantId = required(tenantId, "Invitation tenant id");
		this.workspaceId = required(workspaceId, "Invitation workspace id");
		this.email = normalizeEmail(email);
		this.displayName = normalizeDisplayName(displayName);
		this.tokenHash = required(tokenHash, "Invitation token hash");
		this.roles = validRoles(roles);
		this.expiry = required(expiry, "Invitation expiry");
		this.creator = required(creator, "Invitation creator");
		this.status = required(status, "Invitation status");
	}

	public static OrganizationInvitation pending(UUID id, TenantId tenantId, WorkspaceId workspaceId, String email, String displayName,
			InvitationTokenHash tokenHash, Set<MembershipRole> roles, InvitationExpiry expiry, MembershipId creator) {
		return new OrganizationInvitation(id, tenantId, workspaceId, email, displayName, tokenHash, roles, expiry, creator, InvitationStatus.PENDING);
	}

	public static OrganizationInvitation restore(UUID id, TenantId tenantId, WorkspaceId workspaceId, String email, String displayName,
			InvitationTokenHash tokenHash, Set<MembershipRole> roles, InvitationExpiry expiry, MembershipId creator, InvitationStatus status) {
		return new OrganizationInvitation(id, tenantId, workspaceId, email, displayName, tokenHash, roles, expiry, creator, status);
	}

	public void accept(Clock clock) { ensurePending(clock); status = InvitationStatus.ACCEPTED; }
	public void revoke(Clock clock) { ensurePending(clock); status = InvitationStatus.REVOKED; }
	public void resend(InvitationTokenHash replacementTokenHash, InvitationExpiry replacementExpiry, Clock clock) {
		ensurePending(clock);
		if (replacementExpiry == null || replacementExpiry.hasExpired(clock)) throw new TenantManagementInvariantViolation("Invitation replacement expiry is invalid");
		this.tokenHash = required(replacementTokenHash, "Invitation replacement token hash");
		this.expiry = required(replacementExpiry, "Invitation replacement expiry");
	}
	public void expire(Clock clock) { if (status == InvitationStatus.PENDING && expiry.hasExpired(clock)) status = InvitationStatus.EXPIRED; }
	public boolean hasTokenHash(InvitationTokenHash candidate) { return tokenHash.matches(candidate); }
	private void ensurePending(Clock clock) { expire(Objects.requireNonNull(clock, "Clock is required")); if (status != InvitationStatus.PENDING) throw new TenantManagementInvariantViolation("Invitation is no longer pending"); }

	private static String normalizeEmail(String value) {
		String normalized = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
		if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new TenantManagementInvariantViolation("Invitation email is invalid");
		return normalized;
	}
	private static String normalizeDisplayName(String value) {
		String normalized = value == null ? "" : value.strip();
		if (normalized.isBlank()) throw new TenantManagementInvariantViolation("Invitation display name is invalid");
		return normalized;
	}
	private static Set<MembershipRole> validRoles(Set<MembershipRole> values) {
		if (values == null || values.isEmpty() || values.stream().anyMatch(Objects::isNull) || values.contains(MembershipRole.BUYER)) {
			throw new TenantManagementInvariantViolation("Invitation roles are invalid");
		}
		return Set.copyOf(values);
	}
	private static <T> T required(T value, String label) {
		if (value == null) throw new TenantManagementInvariantViolation(label + " is required");
		return value;
	}

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
