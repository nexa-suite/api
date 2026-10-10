package com.nexa.api.creditreceivables.application.publicapi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditAccountConfigurationQueryTests {
    @Test
    void usedCreditSumsFinancedExposureReceivablesAndReservations() {
        var snapshot = new CreditAccountConfigurationQuery.Snapshot(UUID.randomUUID(), "PEN",
                CreditAccountConfigurationQuery.Status.ACTIVE, new BigDecimal("1000.0000"),
                new BigDecimal("100.0000"), new BigDecimal("250.0000"),
                new BigDecimal("50.0000"), 4L);

        assertThat(snapshot.used()).isEqualByComparingTo("400.0000");
        assertThat(snapshot.availableCredit()).isEqualByComparingTo("600.0000");
    }

    @Test
    void unconfiguredAccountHasExplicitCurrencyAndNullAmountsAndVersion() {
        var snapshot = CreditAccountConfigurationQuery.Snapshot.notConfigured(UUID.randomUUID(), "PEN");

        assertThat(snapshot.status()).isEqualTo(CreditAccountConfigurationQuery.Status.NOT_CONFIGURED);
        assertThat(snapshot.currency()).isEqualTo("PEN");
        assertThat(snapshot.creditLimit()).isNull();
        assertThat(snapshot.used()).isNull();
        assertThat(snapshot.availableCredit()).isNull();
        assertThat(snapshot.version()).isNull();
    }
}
