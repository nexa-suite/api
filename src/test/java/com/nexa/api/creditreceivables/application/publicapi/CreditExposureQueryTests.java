package com.nexa.api.creditreceivables.application.publicapi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CreditExposureQueryTests {
    @Test
    void creditOwnerCalculatesUsedAndAvailableCredit() {
        var snapshot = new CreditExposureQuery.CreditExposureSnapshot("pen", new BigDecimal("1000"),
                new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("50"), true);

        assertThat(snapshot.currency()).isEqualTo("PEN");
        assertThat(snapshot.used()).isEqualByComparingTo("400");
        assertThat(snapshot.availableCredit()).isEqualByComparingTo("600");
    }
}
