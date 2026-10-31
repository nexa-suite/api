package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.application.directorder.service.DirectOrderService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SalesRuntimeDeliveryLeadTimeWiringTests {
    @Test
    void directOrderPolicyUsesTheFixedGlobalBusinessDayRule() {
        DirectOrderService service = new DirectOrderService(null, null,
                Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC));

        var policy = service.deliveryPolicy(SalesTestFixtures.salesContext());

        assertThat(policy.minimumDeliveryLeadDays()).isEqualTo(5);
        assertThat(policy.earliestAllowedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    }
}
