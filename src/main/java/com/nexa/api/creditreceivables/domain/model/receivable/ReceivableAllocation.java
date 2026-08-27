package com.nexa.api.creditreceivables.domain.model.receivable;

import java.math.BigDecimal;

/** Immutable allocation between one payment and one receivable. */
public record ReceivableAllocation(String id, String paymentId, BigDecimal amount) {
    public ReceivableAllocation {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Receivable allocation id is required");
        if (paymentId == null || paymentId.isBlank()) throw new IllegalArgumentException("Receivable allocation payment is required");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Receivable allocation amount is invalid");
    }
}
