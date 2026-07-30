package com.nexa.api.iam.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_session", schema = "iam")
public class RefreshSessionJpaEntity {
	@Id private UUID id;
	@Column(name = "user_id", nullable = false) private UUID userId;
	@Column(name = "membership_id", nullable = false) private UUID membershipId;
	@Column(nullable = false, length = 32) private String surface;
	@JdbcTypeCode(java.sql.Types.CHAR)
	@Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "char(64)") private String tokenHash;
	@Column(name = "family_id", nullable = false) private UUID familyId;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "last_used_at") private Instant lastUsedAt;
	@Column(name = "expires_at", nullable = false) private Instant expiresAt;
	@Column(name = "revoked_at") private Instant revokedAt;
	@Column(name = "family_revoked_at") private Instant familyRevokedAt;
	@Column(name = "replaced_by_session_id") private UUID replacedBySessionId;
	@Version @Column(nullable = false) private long version;

	protected RefreshSessionJpaEntity() {}

	public UUID getId() { return id; }
	public UUID getUserId() { return userId; }
	public UUID getMembershipId() { return membershipId; }
	public String getSurface() { return surface; }
	public String getTokenHash() { return tokenHash; }
	public UUID getFamilyId() { return familyId; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getLastUsedAt() { return lastUsedAt; }
	public Instant getExpiresAt() { return expiresAt; }
	public Instant getRevokedAt() { return revokedAt; }
	public Instant getFamilyRevokedAt() { return familyRevokedAt; }
	public UUID getReplacedBySessionId() { return replacedBySessionId; }
	public void revoke(Instant at) { this.revokedAt = at; }
	public void revokeFamily(Instant at) { this.revokedAt = at; this.familyRevokedAt = at; }
	public void rotateTo(UUID replacementId, Instant at) { this.lastUsedAt = at; this.revokedAt = at; this.replacedBySessionId = replacementId; }

	public static RefreshSessionJpaEntity from(com.nexa.api.iam.application.model.SessionRecord record, String tokenHash) {
		var session = record.session();
		var membershipId = record.subject().policy().membershipId();
		if (membershipId == null || membershipId.isBlank()) throw new IllegalArgumentException("Authentication policy missing membershipId");
		var entity = new RefreshSessionJpaEntity();
		entity.id = UUID.fromString(session.id().value());
		entity.userId = UUID.fromString(session.userAccountId().value());
		entity.membershipId = UUID.fromString(membershipId);
		entity.surface = session.surface().name();
		entity.tokenHash = tokenHash;
		entity.familyId = UUID.fromString(session.refreshTokenFamilyId().value());
		entity.createdAt = session.createdAt();
		entity.expiresAt = session.expiresAt();
		return entity;
	}
}
