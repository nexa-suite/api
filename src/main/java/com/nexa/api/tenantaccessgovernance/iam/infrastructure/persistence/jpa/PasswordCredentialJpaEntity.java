package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_credential", schema = "iam")
public class PasswordCredentialJpaEntity {
	@Id
	@Column(name = "user_id")
	private UUID userId;
	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;
	@Column(nullable = false, length = 32)
	private String algorithm;
	@Column(name = "changed_at", nullable = false)
	private Instant changedAt;

	protected PasswordCredentialJpaEntity() {
	}

	public PasswordCredentialJpaEntity(UUID userId, String passwordHash, String algorithm, Instant changedAt) {
		this.userId = userId;
		this.passwordHash = passwordHash;
		this.algorithm = algorithm;
		this.changedAt = changedAt;
	}

	public String getPasswordHash() { return passwordHash; }
}
