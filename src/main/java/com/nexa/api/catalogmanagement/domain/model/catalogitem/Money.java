package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {
	public Money {
		if (amount == null) throw new CatalogInvariantViolation("Money amount is required");
		if (amount.signum() < 0) throw new CatalogInvariantViolation("Money amount cannot be negative");
		if (amount.scale() > 2) throw new CatalogInvariantViolation("Money amount cannot have more than two decimals");
		if (currency == null) throw new CatalogInvariantViolation("Money currency is required");
		amount = amount.stripTrailingZeros();
	}

	public static Money from(BigDecimal amount, String currencyCode) {
		if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
			throw new CatalogInvariantViolation("Currency must be an uppercase ISO 4217 code");
		}
		try {
			return new Money(amount, Currency.getInstance(currencyCode));
		} catch (IllegalArgumentException exception) {
			throw new CatalogInvariantViolation("Currency must be a valid ISO 4217 code");
		}
	}

	public Money multiply(BigDecimal quantity) {
		Objects.requireNonNull(quantity, "Money quantity is required");
		if (quantity.signum() < 0) throw new CatalogInvariantViolation("Money quantity cannot be negative");
		return new Money(amount.multiply(quantity).setScale(2, RoundingMode.HALF_UP), currency);
	}

	public String currencyCode() {
		return currency.getCurrencyCode();
	}
}
