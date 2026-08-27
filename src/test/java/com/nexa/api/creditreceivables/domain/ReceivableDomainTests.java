package com.nexa.api.creditreceivables.domain;

import com.nexa.api.creditreceivables.domain.model.receivable.Receivable;
import com.nexa.api.creditreceivables.domain.model.receivable.ReceivableAllocation;
import com.nexa.api.creditreceivables.domain.model.receivable.ReceivableStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceivableDomainTests {
    @Test
    void allocationOwnsBalanceAndStatusTransitions() {
        Receivable receivable = Receivable.rehydrate("ar-1", new BigDecimal("100.00"), BigDecimal.ZERO, ReceivableStatus.OPEN);

        receivable.allocate(new BigDecimal("40.00"));
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
        receivable.allocate(new BigDecimal("60.00"));
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PAID);
        assertThatThrownBy(() -> receivable.allocate(BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReceivableAllocation("allocation-1", "payment-1", BigDecimal.TEN).amount())
                .isEqualByComparingTo(BigDecimal.TEN);
    }
}
