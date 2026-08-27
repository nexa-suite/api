package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "authentication_failure", schema = "iam",
		uniqueConstraints = @UniqueConstraint(name = "uq_authentication_failure_key",
			columnNames = {"normalized_identifier", "client_fingerprint"}))
public class AuthenticationFailureJpaEntity {
	@Id private UUID id;
	@Column(name = "normalized_identifier", nullable = false, length = 254) private String normalizedIdentifier;
	@Column(name = "client_fingerprint", nullable = false, length = 128) private String clientFingerprint;
	@Column(name = "failure_count", nullable = false) private int failureCount;
	@Column(name = "window_started_at", nullable = false) private Instant windowStartedAt;
	@Column(name = "last_failure_at", nullable = false) private Instant lastFailureAt;

	protected AuthenticationFailureJpaEntity() { }

	public AuthenticationFailureJpaEntity(String normalizedIdentifier, String clientFingerprint, Instant now) {
		this.id = UUID.randomUUID();
		this.normalizedIdentifier = normalizedIdentifier;
		this.clientFingerprint = clientFingerprint;
		this.failureCount = 0;
		this.windowStartedAt = now;
		this.lastFailureAt = now;
	}

	public int getFailureCount() { return failureCount; }
	public Instant getWindowStartedAt() { return windowStartedAt; }
	public void record(Instant now, java.time.Duration window) {
		if (!windowStartedAt.plus(window).isAfter(now)) {
			failureCount = 0;
			windowStartedAt = now;
		}
		failureCount++;
		lastFailureAt = now;
	}
}
