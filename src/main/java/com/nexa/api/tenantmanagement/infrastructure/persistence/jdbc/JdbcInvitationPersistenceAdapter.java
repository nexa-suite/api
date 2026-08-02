package com.nexa.api.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantmanagement.application.port.out.InvitationPersistencePort;
import com.nexa.api.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.invitation.InvitationExpiry;
import com.nexa.api.tenantmanagement.domain.model.invitation.InvitationStatus;
import com.nexa.api.tenantmanagement.domain.model.invitation.InvitationTokenHash;
import com.nexa.api.tenantmanagement.domain.model.invitation.OrganizationInvitation;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcInvitationPersistenceAdapter implements InvitationPersistencePort {
	private final JdbcTemplate jdbc;

	public JdbcInvitationPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public Optional<InvitationSnapshot> find(String tenantId, String workspaceId, UUID invitationId) {
		return querySnapshot("select id,tenant_id,workspace_id,email,display_name,token_hash,status,expires_at,created_by_membership_id,version,created_at from tenant_management.organization_invitation where tenant_id=? and workspace_id=? and id=?",
				uuid(tenantId), uuid(workspaceId), invitationId);
	}

	@Override
	public List<InvitationSnapshot> findPage(String tenantId, String workspaceId, int page, int pageSize) {
		List<UUID> ids = jdbc.query("select id from tenant_management.organization_invitation where tenant_id=? and workspace_id=? order by created_at desc,id desc limit ? offset ?",
				(rs, row) -> rs.getObject(1, UUID.class), uuid(tenantId), uuid(workspaceId), pageSize, page * pageSize);
		return ids.stream().map(id -> find(tenantId, workspaceId, id).orElseThrow()).toList();
	}

	@Override
	public Optional<InvitationSnapshot> findPendingByEmail(String tenantId, String workspaceId, String normalizedEmail) {
		return jdbc.query("select id from tenant_management.organization_invitation where tenant_id=? and workspace_id=? and normalized_email=? and status='PENDING' limit 1",
				(rs, row) -> rs.getObject(1, UUID.class), uuid(tenantId), uuid(workspaceId), normalizedEmail).stream()
				.map(id -> find(tenantId, workspaceId, id).orElseThrow()).findFirst();
	}

	@Override
	public Optional<UUID> findIdempotent(String tenantId, String idempotencyKey, String requestHash) {
		return jdbc.query("select invitation_id from tenant_management.organization_invitation_idempotency where tenant_id=? and idempotency_key=? and request_hash=?",
				(rs, row) -> rs.getObject(1, UUID.class), uuid(tenantId), idempotencyKey, requestHash).stream().findFirst();
	}

	@Override
	public boolean idempotencyKeyHasDifferentPayload(String tenantId, String idempotencyKey, String requestHash) {
		return jdbc.queryForObject("select exists(select 1 from tenant_management.organization_invitation_idempotency where tenant_id=? and idempotency_key=? and request_hash<>?)", Boolean.class, uuid(tenantId), idempotencyKey, requestHash);
	}

	@Override
	public int saveIdempotency(String tenantId, String idempotencyKey, String requestHash, UUID invitationId) {
		return jdbc.update("insert into tenant_management.organization_invitation_idempotency (tenant_id,idempotency_key,request_hash,invitation_id,created_at) values (?,?,?,?,current_timestamp)", uuid(tenantId), idempotencyKey, requestHash, invitationId);
	}

	@Override
	public int create(OrganizationInvitation invitation, Instant createdAt) {
		int created = jdbc.update("insert into tenant_management.organization_invitation (id,tenant_id,workspace_id,email,normalized_email,display_name,token_hash,status,expires_at,created_by_membership_id,version,created_at,updated_at) values (?,?,?,?,?,?,?,'PENDING',?,?,0,?,?)",
				invitation.id(), invitation.tenantId().value(), invitation.workspaceId().value(), invitation.email(), invitation.email(), invitation.displayName(), invitation.tokenHash().value(), Timestamp.from(invitation.expiry().value()), invitation.creator().value(), Timestamp.from(createdAt), Timestamp.from(createdAt));
		for (MembershipRole role : invitation.roles()) jdbc.update("insert into tenant_management.organization_invitation_role (invitation_id,role) values (?,?)", invitation.id(), role.name());
		return created;
	}

	@Override
	public int rotateToken(String tenantId, UUID invitationId, String tokenHash, Instant expiresAt, long expectedVersion) {
		return jdbc.update("update tenant_management.organization_invitation set token_hash=?,expires_at=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and id=? and status='PENDING' and version=?",
				tokenHash, Timestamp.from(expiresAt), uuid(tenantId), invitationId, expectedVersion);
	}

	@Override
	public int updateStatus(String tenantId, UUID invitationId, String status, Instant changedAt, UUID acceptedUserId, long expectedVersion) {
		return jdbc.update("update tenant_management.organization_invitation set status=?,accepted_user_id=coalesce(?,accepted_user_id),accepted_at=case when ?='ACCEPTED' then ? else accepted_at end,revoked_at=case when ?='REVOKED' then ? else revoked_at end,updated_at=?,version=version+1 where tenant_id=? and id=? and status='PENDING' and version=?",
				status, acceptedUserId, status, Timestamp.from(changedAt), status, Timestamp.from(changedAt), Timestamp.from(changedAt), uuid(tenantId), invitationId, expectedVersion);
	}

	@Override
	public Optional<InvitationSnapshot> findForUpdateByTokenHash(String tokenHash) {
		return querySnapshot("select id,tenant_id,workspace_id,email,display_name,token_hash,status,expires_at,created_by_membership_id,version,created_at from tenant_management.organization_invitation where token_hash=? for update", tokenHash);
	}

	@Override
	public Optional<MembershipRecord> findActiveMembershipByEmail(String workspaceId, String normalizedEmail) {
		return jdbc.query("select m.id,m.user_id from tenant_management.workspace_membership m join iam.user_account u on u.id=m.user_id where m.workspace_id=? and u.normalized_email=? and m.status='ACTIVE'",
				(rs, row) -> new MembershipRecord(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), uuid(workspaceId), normalizedEmail).stream().findFirst();
	}

	@Override
	public Optional<UserRecord> findUserByEmail(String normalizedEmail) {
		return jdbc.query("select id,email,status from iam.user_account where normalized_email=?", (rs, row) -> new UserRecord(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), normalizedEmail).stream().findFirst();
	}

	@Override
	public UUID createUser(String email, String displayName, String passwordHash, Instant now) {
		UUID id = UUID.randomUUID();
		String username = username(email);
		jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',?,?,0)",
				id, email, email, username, username, displayName, "en", Timestamp.from(now), Timestamp.from(now));
		jdbc.update("insert into iam.password_credential (user_id,password_hash,algorithm,changed_at) values (?,?,'bcrypt',?)", id, passwordHash, Timestamp.from(now));
		return id;
	}

	@Override
	public UUID createMembership(String tenantId, String workspaceId, UUID userId, Instant now) {
		UUID membership = UUID.randomUUID();
		jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)", membership, uuid(workspaceId), userId, Timestamp.from(now), Timestamp.from(now));
		return membership;
	}

	@Override
	public void assignRoles(UUID membershipId, String tenantId, String workspaceId, Set<String> roles, Instant now) {
		for (String role : roles) jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) values (?,?,?,?,?)", membershipId, uuid(tenantId), uuid(workspaceId), role, Timestamp.from(now));
	}

	private Optional<InvitationSnapshot> querySnapshot(String sql, Object... args) {
		return jdbc.query(sql, (rs, row) -> {
			UUID id = rs.getObject("id", UUID.class);
			Set<MembershipRole> roles = new LinkedHashSet<>(jdbc.query("select role from tenant_management.organization_invitation_role where invitation_id=? order by role", (roleRs, roleRow) -> MembershipRole.from(roleRs.getString(1)), id));
			OrganizationInvitation invitation = OrganizationInvitation.restore(id, new TenantId(rs.getObject("tenant_id", UUID.class).toString()), new WorkspaceId(rs.getObject("workspace_id", UUID.class).toString()), rs.getString("email"), rs.getString("display_name"), new InvitationTokenHash(rs.getString("token_hash")), roles, new InvitationExpiry(rs.getTimestamp("expires_at").toInstant()), new MembershipId(rs.getObject("created_by_membership_id", UUID.class).toString()), InvitationStatus.from(rs.getString("status")));
			return new InvitationSnapshot(invitation, rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
		}, args).stream().findFirst();
	}

	private static String username(String email) {
		String value = email.substring(0, email.indexOf('@')).toLowerCase(java.util.Locale.ROOT);
		String suffix;
		try {
			suffix = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to derive invitation username", exception);
		}
		String base = value.length() > 53 ? value.substring(0, 53) : value;
		return base + "_" + suffix;
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
