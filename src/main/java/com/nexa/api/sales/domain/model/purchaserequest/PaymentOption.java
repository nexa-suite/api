package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record PaymentOption(String value) {
	public PaymentOption { if (value == null || value.isBlank() || value.trim().length() > 80) throw new SalesInvariantViolation("Payment option is invalid"); value = value.trim(); }
}
