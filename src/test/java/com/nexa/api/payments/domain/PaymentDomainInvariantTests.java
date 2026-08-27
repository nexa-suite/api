package com.nexa.api.payments.domain;

import com.nexa.api.creditreceivables.domain.model.credit.CreditAccount;
import com.nexa.api.payments.domain.model.payment.Payment;
import com.nexa.api.payments.domain.model.payment.PaymentStatus;
import com.nexa.api.creditreceivables.domain.model.receivable.Receivable;
import com.nexa.api.creditreceivables.domain.model.receivable.ReceivableStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentDomainInvariantTests {
    @Test
    void paymentRejectsRegressiveProviderEvents() {
        Payment payment = Payment.rehydrate("pay-1", new BigDecimal("10.00"), PaymentStatus.PROCESSING);

        assertThat(payment.applyProviderStatus(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(payment.applyProviderStatus(PaymentStatus.SUCCEEDED)).isFalse();
        assertThatThrownBy(() -> payment.applyProviderStatus(PaymentStatus.PROCESSING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receivableOwnsAllocationAndStatusConsistency() {
        Receivable receivable = Receivable.rehydrate("ar-1", new BigDecimal("100.00"), BigDecimal.ZERO, ReceivableStatus.OPEN);

        receivable.allocate(new BigDecimal("40.00"));
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
        receivable.allocate(new BigDecimal("60.00"));
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PAID);
        assertThatThrownBy(() -> receivable.allocate(BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Receivable.rehydrate("ar-2", BigDecimal.TEN, BigDecimal.ONE, ReceivableStatus.OPEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creditAccountSerialisablePolicyRejectsOvercommit() {
        CreditAccount account = CreditAccount.rehydrate("credit-1", new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("10.00"));

        account.consume(new BigDecimal("70.00"));
        assertThat(account.available()).isZero();
        assertThatThrownBy(() -> account.consume(BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditAccount.rehydrate("credit-2", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
