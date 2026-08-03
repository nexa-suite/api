package com.nexa.api.iam.infrastructure.jwt;

import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.application.port.out.AuthenticationTokenPort;
import com.nexa.api.iam.domain.model.session.SessionId;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JwtAuthenticationTokenIssuer implements AuthenticationTokenPort {
	private final JwtEncoder encoder;
	private final String issuer;
	private final String audience;
	private final Duration accessTokenTtl;
	private final Duration refreshTokenTtl;
	private final SecureRandom random;

	public JwtAuthenticationTokenIssuer(JwtEncoder encoder, String issuer, String audience, Duration accessTokenTtl,
			Duration refreshTokenTtl, SecureRandom random) {
		this.encoder = Objects.requireNonNull(encoder, "JWT encoder is required");
		this.issuer = requireText(issuer, "JWT issuer");
		this.audience = requireText(audience, "JWT audience");
		this.accessTokenTtl = requirePositive(accessTokenTtl, "Access token TTL");
		this.refreshTokenTtl = requirePositive(refreshTokenTtl, "Refresh token TTL");
		this.random = Objects.requireNonNull(random, "Secure random is required");
	}

	@Override
	public IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt) {
		return issue(subject, issuedAt, null);
	}

	@Override
	public IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt, SessionId sessionId) {
		Objects.requireNonNull(subject, "Authentication subject is required");
		Objects.requireNonNull(issuedAt, "Issue time is required");
		Instant accessExpiresAt = issuedAt.plus(accessTokenTtl);
		Instant refreshExpiresAt = issuedAt.plus(refreshTokenTtl);
		var policy = subject.policy();
		var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
				.subject(subject.userAccountId().value()).claim("email", subject.email().value())
				.claim("tenant_id", policy.tenantId()).claim("tenant_slug", policy.tenantSlug())
				.claim("workspace_id", policy.workspaceId()).claim("workspace_slug", policy.workspaceSlug())
				.claim("membership_id", policy.membershipId()).claim("roles", policy.roles())
				.claim("role_definition_ids", policy.roleDefinitionIds())
				.claim("permissions", policy.permissions()).claim("surface", subject.surface().name())
				.claim("authorization_version", policy.authorizationVersion())
				.issuedAt(issuedAt).notBefore(issuedAt).expiresAt(accessExpiresAt).id(UUID.randomUUID().toString());
		if (sessionId != null) claims.claim("sid", sessionId.value());
		String accessToken = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims.build())).getTokenValue();
		return new IssuedAuthenticationTokens(accessToken, randomRefreshToken(), issuedAt, accessExpiresAt, refreshExpiresAt);
	}

	private String randomRefreshToken() {
		byte[] bytes = new byte[48];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(label + " must be positive");
		return value;
	}

	private static String requireText(String value, String label) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
		return value.trim();
	}
}
