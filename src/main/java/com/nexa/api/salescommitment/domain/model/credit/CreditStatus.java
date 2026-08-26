package com.nexa.api.salescommitment.domain.model.credit;

import java.util.Locale;

public enum CreditStatus {
    AVAILABLE,
    ATTENTION,
    BLOCKED,
    OVERDUE;

    public static CreditStatus from(String value) {
        if (value == null || value.isBlank()) return AVAILABLE;
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT).transform(CreditStatus::valueOf);
    }
}
