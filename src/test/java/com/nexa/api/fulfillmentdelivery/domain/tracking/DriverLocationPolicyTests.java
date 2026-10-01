package com.nexa.api.fulfillmentdelivery.domain.tracking;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class DriverLocationPolicyTests {
    @Test void capturesOnlyValidCoordinatesInsideOperationalDayAndRetentionWindow() {
        Instant now = Instant.parse("2026-10-01T15:00:00Z");
        assertThat(DriverLocationPolicy.valid(-12.1,-77.0,5,now,now.minusSeconds(3600),now)).isTrue();
        assertThat(DriverLocationPolicy.valid(-12.1,-77.0,5,now.minusSeconds(3601),now.minusSeconds(3600),now)).isFalse();
        assertThat(DriverLocationPolicy.valid(Double.NaN,-77.0,5,now,now.minusSeconds(1),now)).isFalse();
        assertThat(DriverLocationPolicy.valid(-12.1,181,5,now,now.minusSeconds(1),now)).isFalse();
        assertThat(DriverLocationPolicy.valid(-12.1,-77.0,5,now.minusSeconds(86401),now.minusSeconds(90000),now)).isFalse();
    }
}
