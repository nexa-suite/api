package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.util.Locale;

/** Canonical payment options shared by the API contract and all sales workflows. */
public enum PaymentOption {
	CREDIT_LINE,
	BANK_TRANSFER,
	CASH,
	CASH_ON_DELIVERY;

	public static PaymentOption from(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return value.trim().toUpperCase(Locale.ROOT).transform(PaymentOption::valueOf);
		} catch (IllegalArgumentException exception) {
			throw new SalesInvariantViolation("Payment option is invalid");
		}
	}
}
