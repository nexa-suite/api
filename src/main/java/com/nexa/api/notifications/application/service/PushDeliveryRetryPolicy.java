package com.nexa.api.notifications.application.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Bounded retry timing for one Tenant push subscription/event delivery. */
@Component
public final class PushDeliveryRetryPolicy {
    public static final int DEFAULT_MAX_ATTEMPTS = 20;
    public static final int DEFAULT_MAX_DELAY_SECONDS = 300;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    private final int maxAttempts;
    private final int maxDelaySeconds;
    private final Clock clock;
    private final DoubleSupplier jitter;

    @Autowired
    public PushDeliveryRetryPolicy(
            @Value("${nexa.tenant-business.notifications.push-retry.max-attempts:20}") int maxAttempts,
            @Value("${nexa.tenant-business.notifications.push-retry.max-delay-seconds:300}") int maxDelaySeconds,
            ObjectProvider<Clock> clockProvider) {
        this(maxAttempts, maxDelaySeconds,
                Objects.requireNonNull(clockProvider, "Clock provider is required").getIfAvailable(Clock::systemUTC),
                Math::random);
    }

    public PushDeliveryRetryPolicy(int maxAttempts, int maxDelaySeconds, Clock clock, DoubleSupplier jitter) {
        if (maxAttempts < 1) throw new IllegalArgumentException("Push retry max attempts must be positive");
        if (maxDelaySeconds < 1) throw new IllegalArgumentException("Push retry max delay must be positive");
        this.maxAttempts = maxAttempts;
        this.maxDelaySeconds = maxDelaySeconds;
        this.clock = Objects.requireNonNull(clock, "Application clock is required");
        this.jitter = Objects.requireNonNull(jitter, "Push retry jitter source is required");
    }

    public int maxAttempts() { return maxAttempts; }

    public Instant now() { return clock.instant(); }

    public Instant claimLeaseUntil(Instant now) {
        return Objects.requireNonNull(now, "Claim time is required").plus(CLAIM_LEASE);
    }

    /** Equal jitter keeps delay in [half exponential ceiling, ceiling]. */
    public Instant retryAt(int attemptNumber) {
        return retryAt(attemptNumber, now());
    }

    public Instant retryAt(int attemptNumber, Instant completedAt) {
        return Objects.requireNonNull(completedAt, "Push attempt completion time is required")
                .plusMillis(retryDelayMillis(attemptNumber));
    }

    public long retryDelayMillis(int attemptNumber) {
        if (attemptNumber < 1) throw new IllegalArgumentException("Push attempt number must be positive");
        int exponent = Math.min(attemptNumber - 1, 31);
        long ceilingSeconds = exponent >= 31 ? maxDelaySeconds : Math.min(1L << exponent, maxDelaySeconds);
        long ceilingMillis = ceilingSeconds * 1_000L;
        long minimumMillis = Math.max(1L, ceilingMillis / 2L);
        double sample = jitter.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0.0d || sample >= 1.0d) {
            throw new IllegalStateException("Push retry jitter must be in [0,1)");
        }
        long span = ceilingMillis - minimumMillis + 1L;
        return minimumMillis + (long) Math.floor(sample * span);
    }
}
