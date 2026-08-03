package com.nexa.api.sales.domain.model.commercial;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record PaymentTerms(String code, String label, int dueDays, boolean credit) {
    public PaymentTerms {
        code = required(code, "Payment terms code", 80);
        label = required(label, "Payment terms label", 160);
        if (dueDays < 0 || dueDays > 3650) throw new SalesInvariantViolation("Payment terms due days are invalid");
        if (!credit && dueDays != 0) throw new SalesInvariantViolation("Cash payment terms cannot have due days");
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
