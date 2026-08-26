package com.nexa.api.shared.infrastructure.events;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalOutboxIdentityTests {

    @Test
    void occurrenceKeyAllowsRepeatedLifecycleFactsForOneAggregate() {
        UUID aggregateId = UUID.randomUUID();

        UUID first = CanonicalOutbox.eventId("DELIVERY_FAILED", "DispatchOrder", aggregateId, "attempt-1");
        UUID second = CanonicalOutbox.eventId("DELIVERY_FAILED", "DispatchOrder", aggregateId, "attempt-2");

        assertThat(second).isNotEqualTo(first);
    }
}
