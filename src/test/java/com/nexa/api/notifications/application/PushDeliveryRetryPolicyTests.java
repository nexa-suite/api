package com.nexa.api.notifications.application;

import com.nexa.api.notifications.application.service.PushDeliveryRetryPolicy;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushDeliveryRetryPolicyTests {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    void retryDelayUsesDeterministicBoundedJitterAndConfiguredCap() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var minimumJitter = new PushDeliveryRetryPolicy(20, 300, clock, () -> 0.0d);
        var maximumJitter = new PushDeliveryRetryPolicy(20, 300, clock, () -> 0.999999d);

        assertThat(minimumJitter.retryAt(1)).isEqualTo(NOW.plusMillis(500));
        assertThat(maximumJitter.retryAt(1)).isEqualTo(NOW.plusSeconds(1));
        assertThat(minimumJitter.retryAt(10)).isEqualTo(NOW.plusSeconds(150));
        assertThat(maximumJitter.retryAt(10)).isEqualTo(NOW.plusSeconds(300));
        assertThat(minimumJitter.maxAttempts()).isEqualTo(20);
    }

    @Test
    void retryPolicyRejectsInvalidLimitsAndJitterValues() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new PushDeliveryRetryPolicy(0, 300, clock, () -> 0.5d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushDeliveryRetryPolicy(20, 0, clock, () -> 0.5d))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PushDeliveryRetryPolicy(20, 300, clock, () -> 1.0d).retryDelayMillis(1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contextUsesConfiguredClockAndFallsBackToUtcWhenAbsent() {
        Clock configured = Clock.fixed(NOW, ZoneOffset.ofHours(-5));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Clock.class, () -> configured);
            context.registerBean(PushDeliveryRetryPolicy.class);
            context.refresh();

            assertThat(context.getBean(PushDeliveryRetryPolicy.class).now()).isEqualTo(NOW);
        }

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PushDeliveryRetryPolicy.class);
            context.refresh();

            var policy = context.getBean(PushDeliveryRetryPolicy.class);
            assertThat(policy.now()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
        }
    }
}
