package com.nexa.api.sales.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record PriceSnapshot(BigDecimal amount, String currency) {
	public PriceSnapshot { amount = Objects.requireNonNull(amount); currency = Objects.requireNonNull(currency).trim().toUpperCase(java.util.Locale.ROOT); if (amount.signum() < 0 || currency.length() != 3) throw new IllegalArgumentException("Price snapshot is invalid"); }
}
