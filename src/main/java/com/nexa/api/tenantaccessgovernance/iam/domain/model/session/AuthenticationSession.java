package com.nexa.api.tenantaccessgovernance.iam.domain.model.session;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.time.Instant;
import java.util.Objects;

public final class AuthenticationSession {
	private final SessionId id;
	private final UserAccountId userAccountId;
	private final ClientSurface surface;
	private final RefreshTokenFamilyId refreshTokenFamilyId;
	private final Instant createdAt;
	private final Instant expiresAt;
	private AuthenticationSessionStatus status;
	private Instant revokedAt;

	private AuthenticationSession(SessionId id, UserAccountId userAccountId, ClientSurface surface,
			RefreshTokenFamilyId refreshTokenFamilyId, Instant createdAt, Instant expiresAt) {
		this.id = required(id, "Session id");
		this.userAccountId = required(userAccountId, "User account id");
		this.surface = required(surface, "Client surface");
		this.refreshTokenFamilyId = required(refreshTokenFamilyId, "Refresh token family id");
		this.createdAt = required(createdAt, "Session creation time");
		this.expiresAt = required(expiresAt, "Session expiration time");
		if (!expiresAt.isAfter(createdAt)) throw new SessionInvariantViolation("Session must expire after creation");
		this.status = AuthenticationSessionStatus.ACTIVE;
	}

	public static AuthenticationSession start(SessionId id, UserAccountId userAccountId, ClientSurface surface,
			RefreshTokenFamilyId refreshTokenFamilyId, Instant createdAt, Instant expiresAt) {
		return new AuthenticationSession(id, userAccountId, surface, refreshTokenFamilyId, createdAt, expiresAt);
	}

	public SessionId id() { return id; }
	public UserAccountId userAccountId() { return userAccountId; }
	public ClientSurface surface() { return surface; }
	public RefreshTokenFamilyId refreshTokenFamilyId() { return refreshTokenFamilyId; }
	public Instant createdAt() { return createdAt; }
	public Instant expiresAt() { return expiresAt; }
	public AuthenticationSessionStatus status() { return status; }
	public Instant revokedAt() { return revokedAt; }

	public boolean isActive(Instant reference) {
		Objects.requireNonNull(reference, "Reference time is required");
		return status == AuthenticationSessionStatus.ACTIVE && expiresAt.isAfter(reference);
	}

	public void revoke(Instant revokedAt) {
		this.revokedAt = required(revokedAt, "Revocation time");
		this.status = AuthenticationSessionStatus.REVOKED;
	}

	private static <T> T required(T value, String label) {
		return Objects.requireNonNull(value, label + " is required");
	}
}
