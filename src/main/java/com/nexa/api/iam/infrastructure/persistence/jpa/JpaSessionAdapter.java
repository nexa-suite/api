package com.nexa.api.iam.infrastructure.persistence.jpa;

import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.model.RefreshRotation;
import com.nexa.api.iam.application.model.SessionRecord;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.application.port.out.SessionPort;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.iam.domain.model.session.RefreshTokenFamilyId;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

@Repository
@Profile("!test")
public class JpaSessionAdapter implements SessionPort {
	private final RefreshSessionJpaRepository sessions;
	private final UserAccountJpaRepository users;
	private final AccessPolicyPort accessPolicies;
	private final JdbcTemplate jdbc;
	private final JwtDecoder decoder;
	private final Duration accessTokenTtl;

	public JpaSessionAdapter(RefreshSessionJpaRepository sessions, UserAccountJpaRepository users, AccessPolicyPort accessPolicies,
			JdbcTemplate jdbc, JwtDecoder decoder,
			@Value("${nexa.security.access-token-ttl:PT15M}") Duration accessTokenTtl) {
		this.sessions = sessions;
		this.users = users;
		this.accessPolicies = accessPolicies;
		this.jdbc = jdbc;
		this.decoder = decoder;
		this.accessTokenTtl = accessTokenTtl;
	}

	@Override
	@Transactional
	public SessionRecord start(AuthenticationSession session, AuthenticationSubject subject, IssuedAuthenticationTokens tokens) {
		sessions.save(RefreshSessionJpaEntity.from(new SessionRecord(session, subject, tokens), sha256(tokens.refreshToken())));
		return new SessionRecord(session, subject, tokens);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SessionRecord> findByAccessToken(String accessToken) {
		try {
			var jwt = decoder.decode(accessToken);
			String value = jwt.getClaimAsString("sid");
			if (value == null) return Optional.empty();
			return sessions.findById(UUID.fromString(value)).flatMap(entity -> toRecord(entity, accessToken, null, jwt.getIssuedAt(), jwt.getExpiresAt()));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SessionRecord> findBySessionId(SessionId sessionId) {
		try {
			return sessions.findById(UUID.fromString(sessionId.value())).flatMap(this::toValidationRecord);
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SessionRecord> findByRefreshToken(String refreshToken) {
		return sessions.findByTokenHash(sha256(refreshToken)).flatMap(entity -> toRecord(entity, null, refreshToken, null, null));
	}

	@Override
	@Transactional
	public RefreshRotation rotateRefreshToken(String presentedRefreshToken, SessionRecord replacement, Instant rotatedAt) {
		Optional<RefreshSessionJpaEntity> current = sessions.findByTokenHashForUpdate(sha256(presentedRefreshToken));
		if (current.isEmpty()) return RefreshRotation.invalid();
		RefreshSessionJpaEntity entity = current.get();
		if (entity.getRevokedAt() != null) return RefreshRotation.reused();
		if (!entity.getExpiresAt().isAfter(rotatedAt)) return RefreshRotation.invalid();
		entity.rotateTo(UUID.fromString(replacement.session().id().value()), rotatedAt);
		sessions.save(entity);
		sessions.save(RefreshSessionJpaEntity.from(replacement, sha256(replacement.refreshToken())));
		return RefreshRotation.rotated(replacement);
	}

	@Override
	@Transactional
	public void revoke(SessionId sessionId, Instant revokedAt) {
		sessions.findById(UUID.fromString(sessionId.value())).ifPresent(entity -> entity.revoke(revokedAt));
	}

	@Override
	@Transactional
	public void revoke(SessionId sessionId, UserAccountId userId, ClientSurface surface, Instant revokedAt) {
		sessions.findById(UUID.fromString(sessionId.value())).ifPresent(entity -> {
			if (entity.getUserId().equals(UUID.fromString(userId.value())) && entity.getSurface().equals(surface.name())) {
				entity.revoke(revokedAt);
			}
		});
	}

	@Override
	@Transactional
	public void revokeFamily(RefreshTokenFamilyId familyId, Instant revokedAt) {
		sessions.findByFamilyId(UUID.fromString(familyId.value())).forEach(entity -> entity.revokeFamily(revokedAt));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isFamilyRevoked(RefreshTokenFamilyId familyId) {
		return sessions.findByFamilyId(UUID.fromString(familyId.value())).stream()
				.anyMatch(entity -> entity.getFamilyRevokedAt() != null);
	}

	@Override
	@Transactional(readOnly = true)
	public OptionalLong findAuthorizationVersion(SessionId sessionId) {
		try {
			return jdbc.query("select coalesce((select authorization_version from tenant_management.membership_authorization_state a where a.membership_id=m.id),m.version) "
					+ "from iam.refresh_session s join tenant_management.workspace_membership m on m.id=s.membership_id where s.id=?",
				rs -> rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty(), UUID.fromString(sessionId.value()));
		} catch (RuntimeException exception) {
			return OptionalLong.empty();
		}
	}

	private Optional<SessionRecord> toRecord(RefreshSessionJpaEntity entity, String accessToken, String refreshToken,
			Instant accessIssuedAt, Instant accessExpiresAt) {
		try {
			UserAccountId userId = new UserAccountId(entity.getUserId().toString());
			ClientSurface surface = ClientSurface.valueOf(entity.getSurface());
			String workspaceSlug = jdbc.query("select w.slug from tenant_management.workspace_membership m "
					+ "join tenant_management.workspace w on w.id = m.workspace_id where m.id = ?",
				(rs, row) -> rs.getString("slug"), entity.getMembershipId()).stream().findFirst().orElse(null);
			if (workspaceSlug == null) return Optional.empty();
			AccessPolicy policy = accessPolicies.findFor(userId, workspaceSlug, surface).orElse(null);
			if (policy == null) return Optional.empty();
			var user = users.findById(entity.getUserId()).orElse(null);
			if (user == null) return Optional.empty();
			var subject = new AuthenticationSubject(userId, new EmailAddress(user.getEmail()), surface, policy);
			var session = AuthenticationSession.start(new SessionId(entity.getId().toString()), userId, surface,
					new RefreshTokenFamilyId(entity.getFamilyId().toString()), entity.getCreatedAt(), entity.getExpiresAt());
			if (entity.getRevokedAt() != null) session.revoke(entity.getRevokedAt());
			Instant issued = accessIssuedAt == null ? entity.getCreatedAt() : accessIssuedAt;
			Instant expires = accessExpiresAt == null ? issued.plus(accessTokenTtl) : accessExpiresAt;
			var tokens = new IssuedAuthenticationTokens(accessToken == null ? "persisted-access-token" : accessToken,
					refreshToken == null ? "persisted-refresh-token" : refreshToken, issued, expires, entity.getExpiresAt());
			return Optional.of(new SessionRecord(session, subject, tokens));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	/** Session validity is independent from current membership authorization. */
	private Optional<SessionRecord> toValidationRecord(RefreshSessionJpaEntity entity) {
		try {
			UserAccountJpaEntity user = users.findById(entity.getUserId()).orElse(null);
			if (user == null || !"ACTIVE".equals(user.getStatus())) return Optional.empty();
			ClientSurface surface = ClientSurface.valueOf(entity.getSurface());
			AccessPolicy policy = new AccessPolicy(surface, Set.of("SESSION_VALIDATION"), Set.of(), null, null, null, null,
				entity.getMembershipId().toString(), user.getDisplayName(), user.getPreferredLanguage());
			AuthenticationSubject subject = new AuthenticationSubject(new UserAccountId(entity.getUserId().toString()),
				new EmailAddress(user.getEmail()), surface, policy);
			AuthenticationSession session = AuthenticationSession.start(new SessionId(entity.getId().toString()), subject.userAccountId(),
				surface, new RefreshTokenFamilyId(entity.getFamilyId().toString()), entity.getCreatedAt(), entity.getExpiresAt());
			if (entity.getRevokedAt() != null) session.revoke(entity.getRevokedAt());
			Instant expires = entity.getCreatedAt().plus(accessTokenTtl);
			IssuedAuthenticationTokens tokens = new IssuedAuthenticationTokens("persisted-access-token", "persisted-refresh-token",
				entity.getCreatedAt(), expires, entity.getExpiresAt());
			return Optional.of(new SessionRecord(session, subject, tokens));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to hash session token", exception);
		}
	}
}
