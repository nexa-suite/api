package com.nexa.api.creditreceivables.domain;

import com.nexa.api.creditreceivables.domain.model.credit.CreditAccount;
import com.nexa.api.creditreceivables.domain.model.credit.CreditReservation;
import com.nexa.api.creditreceivables.domain.model.credit.CreditReservationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditAccountDomainTests {
    @Test
    void reservationsCanBeConsumedOrReleasedWithoutOvercommit() {
        CreditAccount account = CreditAccount.rehydrate("credit-1", new BigDecimal("100.00"), new BigDecimal("20.00"), BigDecimal.ZERO);
        CreditReservation reservation = CreditReservation.reserve("reservation-1", new BigDecimal("30.00"));

        account.reserve(reservation.amount());
        assertThat(account.available()).isEqualByComparingTo(new BigDecimal("50.00"));
        reservation.consume();
        account.consumeReservation(reservation.amount());
        assertThat(reservation.status()).isEqualTo(CreditReservationStatus.CONSUMED);
        assertThat(account.exposure()).isEqualByComparingTo(new BigDecimal("50.00"));

        CreditReservation released = CreditReservation.reserve("reservation-2", new BigDecimal("10.00"));
        account.reserve(released.amount());
        released.release();
        account.releaseReservation(new BigDecimal("10.00"));
        assertThat(account.available()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThatThrownBy(released::consume).isInstanceOf(IllegalStateException.class);
    }
}
