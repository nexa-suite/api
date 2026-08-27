package com.nexa.api.fulfillmentdelivery.domain;

import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttempt;
import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryAttemptTests {
    @Test
    void finalAndTransientOutcomesAreDistinguished() {
        DeliveryAttempt failed = new DeliveryAttempt(1, DeliveryAttemptOutcome.FAILED,
                BigDecimal.ONE, BigDecimal.ZERO, "Buyer unavailable");
        DeliveryAttempt partial = new DeliveryAttempt(2, DeliveryAttemptOutcome.PARTIAL,
                new BigDecimal("5"), new BigDecimal("3"), null);

        assertThat(failed.isFinal()).isFalse();
        assertThat(partial.isFinal()).isTrue();
    }

    @Test
    void finalFailureOutcomesRequireReasonAndNeverReceiveMoreThanAttempted() {
        assertThatThrownBy(() -> new DeliveryAttempt(1, DeliveryAttemptOutcome.ABSENT,
                BigDecimal.ONE, BigDecimal.ZERO, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryAttempt(1, DeliveryAttemptOutcome.DELIVERED,
                BigDecimal.ONE, new BigDecimal("2"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryAttempt(0, DeliveryAttemptOutcome.DELIVERED,
                BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
