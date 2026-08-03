package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.model.RefreshRotation;
import com.nexa.api.iam.application.model.SessionRecord;
import com.nexa.api.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.iam.domain.model.access.ClientSurface;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

public interface SessionPort {
	SessionRecord start(AuthenticationSession session, AuthenticationSubject subject, IssuedAuthenticationTokens tokens);

	Optional<SessionRecord> findByAccessToken(String accessToken);

	default Optional<SessionRecord> findBySessionId(SessionId sessionId) { return Optional.empty(); }

	Optional<SessionRecord> findByRefreshToken(String refreshToken);

	/**
	 * Atomically consumes the presented refresh token and stores its replacement.
	 * The lookup must retain enough metadata for a previously consumed token to be reported as REUSED;
	 * otherwise the application cannot distinguish reuse from an unknown token. A reused token must be
	 * reported as REUSED so the application can revoke the complete family.
	 */
	RefreshRotation rotateRefreshToken(String presentedRefreshToken, SessionRecord replacement, Instant rotatedAt);

	void revoke(SessionId sessionId, Instant revokedAt);

	default void revoke(SessionId sessionId, UserAccountId userId, ClientSurface surface, Instant revokedAt) {
		revoke(sessionId, revokedAt);
	}

	void revokeFamily(RefreshTokenFamilyId familyId, Instant revokedAt);

	default boolean isFamilyRevoked(RefreshTokenFamilyId familyId) { return false; }

	/** Returns the current authorization version of the membership bound to a session. */
	default OptionalLong findAuthorizationVersion(SessionId sessionId) { return OptionalLong.empty(); }
}
