package com.nexa.api.sales.domain.model.payment;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

public record PaymentSnapshot(PaymentOption option, String termsCode, BigDecimal amount, String currency,
                              boolean creditAuthorized) {
    public PaymentSnapshot {
        option = Objects.requireNonNull(option, "Payment option is required");
        termsCode = required(termsCode, "Payment terms code", 80);
        amount = Objects.requireNonNull(amount, "Payment amount is required");
        if (amount.signum() < 0) throw new SalesInvariantViolation("Payment amount cannot be negative");
        currency = required(currency, "Payment currency", 3).toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw new SalesInvariantViolation("Payment currency is invalid");
        if (option == PaymentOption.CREDIT_LINE && !creditAuthorized) {
            throw new SalesInvariantViolation("Credit payment is not authorized");
        }
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
