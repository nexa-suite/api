package com.nexa.api.iam.infrastructure.persistence.jpa;

import com.nexa.api.iam.application.model.LoginIdentifier;
import com.nexa.api.iam.application.port.out.AuthenticationThrottlePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

@Repository
@Profile("!test")
public class JpaAuthenticationThrottleAdapter implements AuthenticationThrottlePort {
	private final AuthenticationFailureJpaRepository failures;
	private final JdbcTemplate jdbc;
	private final int maxFailures;
	private final Duration window;
	private final int cleanupBatchSize;

	@Autowired
	public JpaAuthenticationThrottleAdapter(JdbcTemplate jdbc,
			@Value("${nexa.security.throttle.max-failures:5}") int maxFailures,
			@Value("${nexa.security.throttle.window:PT15M}") Duration window,
			@Value("${nexa.security.throttle.cleanup-batch-size:100}") int cleanupBatchSize) {
		this.failures = null;
		this.jdbc = jdbc;
		this.maxFailures = maxFailures;
		this.window = window;
		this.cleanupBatchSize = Math.max(1, Math.min(cleanupBatchSize, 1000));
	}

	/** Compatibility constructor for isolated adapter tests; runtime uses the atomic JDBC path. */
	@Deprecated
	public JpaAuthenticationThrottleAdapter(AuthenticationFailureJpaRepository failures, int maxFailures, Duration window) {
		this.failures = failures;
		this.jdbc = null;
		this.maxFailures = maxFailures;
		this.window = window;
		this.cleanupBatchSize = 100;
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isThrottled(LoginIdentifier identifier, String clientFingerprint, Instant now) {
		if (jdbc != null) {
			return jdbc.query("select failure_count, window_started_at from iam.authentication_failure where normalized_identifier=? and client_fingerprint=?",
			(org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next() && rs.getInt(1) >= maxFailures && rs.getTimestamp(2).toInstant().plus(window).isAfter(now),
				identifier.value(), clientFingerprint);
		}
		return failures.findByNormalizedIdentifierAndClientFingerprint(identifier.value(), clientFingerprint)
				.map(failure -> failure.getWindowStartedAt().plus(window).isAfter(now)
						&& failure.getFailureCount() >= maxFailures)
				.orElse(false);
	}

	@Override
	@Transactional
	public void recordFailure(LoginIdentifier identifier, String clientFingerprint, Instant now) {
		if (jdbc != null) {
			Instant cutoff = now.minus(window);
			jdbc.update("insert into iam.authentication_failure (id,normalized_identifier,client_fingerprint,failure_count,window_started_at,last_failure_at) values (?,?,?,?,?,?) "
					+ "on conflict (normalized_identifier,client_fingerprint) do update set failure_count=case when iam.authentication_failure.window_started_at <= ? then 1 else iam.authentication_failure.failure_count + 1 end, "
					+ "window_started_at=case when iam.authentication_failure.window_started_at <= ? then ? else iam.authentication_failure.window_started_at end, last_failure_at=?",
					UUID.randomUUID(), identifier.value(), clientFingerprint, 1, Timestamp.from(now), Timestamp.from(now),
					Timestamp.from(cutoff), Timestamp.from(cutoff), Timestamp.from(now), Timestamp.from(now));
			cleanup(now);
			return;
		}
		var failure = failures.findForUpdate(identifier.value(), clientFingerprint)
				.orElseGet(() -> new AuthenticationFailureJpaEntity(identifier.value(), clientFingerprint, now));
		failure.record(now, window);
		failures.save(failure);
	}

	@Override
	@Transactional
	public void clear(LoginIdentifier identifier, String clientFingerprint) {
		if (jdbc != null) {
			jdbc.update("delete from iam.authentication_failure where normalized_identifier=? and client_fingerprint=?", identifier.value(), clientFingerprint);
			return;
		}
		failures.findByNormalizedIdentifierAndClientFingerprint(identifier.value(), clientFingerprint).ifPresent(failures::delete);
	}

	private void cleanup(Instant now) {
		jdbc.update("delete from iam.authentication_failure where id in (select id from iam.authentication_failure where last_failure_at < ? order by last_failure_at, id limit ?)",
				Timestamp.from(now.minus(window.multipliedBy(2))), cleanupBatchSize);
	}
}
