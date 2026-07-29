package com.nexa.api.iam.infrastructure.persistence.jpa;

import com.nexa.api.iam.application.model.LoginIdentifier;
import com.nexa.api.iam.application.port.out.AuthenticationThrottlePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Repository
@Profile("!test")
public class JpaAuthenticationThrottleAdapter implements AuthenticationThrottlePort {
	private final AuthenticationFailureJpaRepository failures;
	private final int maxFailures;
	private final Duration window;

	public JpaAuthenticationThrottleAdapter(AuthenticationFailureJpaRepository failures,
			@Value("${nexa.security.throttle.max-failures:5}") int maxFailures,
			@Value("${nexa.security.throttle.window:PT15M}") Duration window) {
		this.failures = failures;
		this.maxFailures = maxFailures;
		this.window = window;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isThrottled(LoginIdentifier identifier, String clientFingerprint, Instant now) {
		return failures.findByNormalizedIdentifierAndClientFingerprint(identifier.value(), clientFingerprint)
				.map(failure -> failure.getWindowStartedAt().plus(window).isAfter(now)
						&& failure.getFailureCount() >= maxFailures)
				.orElse(false);
	}

	@Override
	@Transactional
	public void recordFailure(LoginIdentifier identifier, String clientFingerprint, Instant now) {
		var failure = failures.findForUpdate(identifier.value(), clientFingerprint)
				.orElseGet(() -> new AuthenticationFailureJpaEntity(identifier.value(), clientFingerprint, now));
		failure.record(now, window);
		failures.save(failure);
	}

	@Override
	@Transactional
	public void clear(LoginIdentifier identifier, String clientFingerprint) {
		failures.findByNormalizedIdentifierAndClientFingerprint(identifier.value(), clientFingerprint).ifPresent(failures::delete);
	}
}
