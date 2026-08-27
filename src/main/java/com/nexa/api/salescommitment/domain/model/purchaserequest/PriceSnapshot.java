package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;

public record PriceSnapshot(BigDecimal amount, String currency) {
	public PriceSnapshot {
		if (amount == null || amount.signum() < 0 || currency == null || !currency.trim().matches("[A-Za-z]{3}")) throw new SalesInvariantViolation("Price snapshot is invalid");
		currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
	}
}
