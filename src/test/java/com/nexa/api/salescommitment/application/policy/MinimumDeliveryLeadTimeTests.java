package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadTimeViolationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinimumDeliveryLeadTimeTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsDatesBeforeEarliestCalendarDayAndProvidesTheAcceptedBoundary() {
        MinimumDeliveryLeadTime policy = new MinimumDeliveryLeadTime(3);

        assertThat(policy.earliestAllowedDate(CLOCK)).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThatThrownBy(() -> policy.validate(LocalDate.of(2026, 10, 23), CLOCK))
                .isInstanceOfSatisfying(MinimumDeliveryLeadTimeViolationException.class, failure -> {
                    assertThat(failure.earliestAllowedDate()).isEqualTo(LocalDate.of(2026, 10, 24));
                    assertThat(failure.minimumCalendarDays()).isEqualTo(3);
                });
        policy.validate(LocalDate.of(2026, 10, 24), CLOCK);
    }

    @Test
    void zeroDayPolicyAllowsTodayAndLegacyOptionalDate() {
        MinimumDeliveryLeadTime policy = new MinimumDeliveryLeadTime(0);

        policy.validate(LocalDate.of(2026, 10, 21), CLOCK);
        policy.validate(null, CLOCK);
    }
}
