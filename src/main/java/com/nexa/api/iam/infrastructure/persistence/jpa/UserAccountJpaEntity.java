package com.nexa.api.iam.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_account", schema = "iam")
public class UserAccountJpaEntity {
	@Id
	private UUID id;
	@Column(nullable = false, length = 254)
	private String email;
	@Column(name = "normalized_email", nullable = false, unique = true, length = 254)
	private String normalizedEmail;
	@Column(nullable = false, length = 64)
	private String username;
	@Column(name = "normalized_username", nullable = false, unique = true, length = 64)
	private String normalizedUsername;
	@Column(name = "display_name", nullable = false, length = 160)
	private String displayName;
	@Column(name = "preferred_language", nullable = false, length = 8)
	private String preferredLanguage;
	@Column(nullable = false, length = 32)
	private String status;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
	@Version
	@Column(nullable = false)
	private long version;

	protected UserAccountJpaEntity() {
	}

	public UserAccountJpaEntity(UUID id, String email, String username, String displayName,
			String preferredLanguage, String status, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.email = email;
		this.normalizedEmail = email.toLowerCase(java.util.Locale.ROOT);
		this.username = username;
		this.normalizedUsername = username.toLowerCase(java.util.Locale.ROOT);
		this.displayName = displayName;
		this.preferredLanguage = preferredLanguage;
		this.status = status;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public UUID getId() { return id; }
	public String getEmail() { return email; }
	public String getUsername() { return username; }
	public String getDisplayName() { return displayName; }
	public String getPreferredLanguage() { return preferredLanguage; }
	public String getStatus() { return status; }
}
