package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.model.RefreshRotation;
import com.nexa.api.iam.application.model.SessionRecord;
import com.nexa.api.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.iam.domain.model.session.SessionId;

import java.time.Instant;
import java.util.Optional;

public interface SessionPort {
	SessionRecord start(AuthenticationSession session, AuthenticationSubject subject, IssuedAuthenticationTokens tokens);

	Optional<SessionRecord> findByAccessToken(String accessToken);

	Optional<SessionRecord> findByRefreshToken(String refreshToken);

	/**
	 * Atomically consumes the presented refresh token and stores its replacement.
	 * The lookup must retain enough metadata for a previously consumed token to be reported as REUSED;
	 * otherwise the application cannot distinguish reuse from an unknown token. A reused token must be
	 * reported as REUSED so the application can revoke the complete family.
	 */
	RefreshRotation rotateRefreshToken(String presentedRefreshToken, SessionRecord replacement, Instant rotatedAt);

	void revoke(SessionId sessionId, Instant revokedAt);

	void revokeFamily(RefreshTokenFamilyId familyId, Instant revokedAt);
}
