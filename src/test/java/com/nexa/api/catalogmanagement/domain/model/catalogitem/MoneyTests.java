package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTests {
	@Test
	void acceptsZeroAndPositiveAmountsWithOneOrTwoDecimals() {
		assertThat(Money.from(BigDecimal.ZERO, "PEN").amount()).isEqualByComparingTo("0");
		assertThat(Money.from(new BigDecimal("10.5"), "PEN").amount()).isEqualByComparingTo("10.5");
		assertThat(Money.from(new BigDecimal("10.50"), "PEN").currency()).isEqualTo(Currency.getInstance("PEN"));
	}

	@Test
	void rejectsNegativeMoreThanTwoDecimalsAndInvalidCurrency() {
		assertThatThrownBy(() -> Money.from(new BigDecimal("-0.01"), "PEN")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> Money.from(new BigDecimal("1.001"), "PEN")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> Money.from(new BigDecimal("1.00"), "pen")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> Money.from(new BigDecimal("1.00"), "ZZZ")).isInstanceOf(CatalogInvariantViolation.class);
	}

	@Test
	void equalityUsesNormalizedAmountAndCurrency() {
		assertThat(Money.from(new BigDecimal("10.50"), "PEN"))
				.isEqualTo(Money.from(new BigDecimal("10.5"), "PEN"));
	}
}
