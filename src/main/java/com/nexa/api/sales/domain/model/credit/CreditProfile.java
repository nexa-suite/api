package com.nexa.api.sales.domain.model.credit;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;
import java.util.Objects;

public record CreditProfile(BigDecimal limit, BigDecimal used, CreditStatus status) {
    public CreditProfile {
        limit = nonNegative(limit, "Credit limit");
        used = nonNegative(used, "Credit used");
        status = Objects.requireNonNull(status, "Credit status is required");
    }

    public BigDecimal available() {
        return limit.subtract(used).max(BigDecimal.ZERO);
    }

    public boolean canAuthorize(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) return false;
        return status != CreditStatus.BLOCKED && status != CreditStatus.OVERDUE
                && available().compareTo(amount) >= 0;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) throw new SalesInvariantViolation(label + " cannot be negative");
        return value;
    }
}
