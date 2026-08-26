package com.nexa.api.catalogcommercialpolicy.domain.model.pricing;

import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricePeriodOverlapTests {
	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant END = Instant.parse("2026-02-01T00:00:00Z");

	@Test
	void containsUsesInclusiveStartAndExclusiveFiniteEnd() {
		PricePeriod period = new PricePeriod(START, END);

		assertThat(period.contains(START)).isTrue();
		assertThat(period.contains(START.plusSeconds(1))).isTrue();
		assertThat(period.contains(END.minusSeconds(1))).isTrue();
		assertThat(period.contains(END)).isFalse();
		assertThat(period.contains(START.minusSeconds(1))).isFalse();
	}

	@Test
	void containsSupportsAnOpenEndedPeriod() {
		PricePeriod period = new PricePeriod(START, null);

		assertThat(period.validUntil()).isNull();
		assertThat(period.contains(START)).isTrue();
		assertThat(period.contains(END)).isTrue();
		assertThat(period.contains(START.minusSeconds(1))).isFalse();
		assertThatThrownBy(() -> period.contains(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void validatesRequiredAndOrderedPeriodBounds() {
		assertThatThrownBy(() -> new PricePeriod(null, END)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new PricePeriod(START, START)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PricePeriod(START, START.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void finitePeriodsOverlapOnlyWhenTheirHalfOpenRangesIntersect() {
		PricePeriod base = new PricePeriod(START, END);

		assertThat(base.overlaps(new PricePeriod(START.plusSeconds(1), END.plusSeconds(1)))).isTrue();
		assertThat(base.overlaps(new PricePeriod(START.plusSeconds(1), END.minusSeconds(1)))).isTrue();
		assertThat(base.overlaps(new PricePeriod(END, END.plusSeconds(1)))).isFalse();
		assertThat(base.overlaps(new PricePeriod(START.minusSeconds(1), START))).isFalse();
		assertThat(base.overlaps(new PricePeriod(START.minusSeconds(1), START.plusSeconds(1)))).isTrue();
	}

	@Test
	void openEndedPeriodsOverlapAccordingToTheirFiniteStartsAndEnds() {
		PricePeriod openEnded = new PricePeriod(START, null);

		assertThat(openEnded.overlaps(new PricePeriod(END, null))).isTrue();
		assertThat(openEnded.overlaps(new PricePeriod(END.minusSeconds(1), END))).isTrue();
		assertThat(openEnded.overlaps(new PricePeriod(START.minusSeconds(1), START))).isFalse();
		assertThat(openEnded.overlaps(new PricePeriod(START.minusSeconds(1), END))).isTrue();
		assertThatThrownBy(() -> openEnded.overlaps(null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void priceBindsMoneyAndPeriodWithoutRepeatingMoneyRules() {
		Money money = Money.from(new BigDecimal("10.50"), "PEN");
		PricePeriod period = new PricePeriod(START, END);
		Price price = new Price(UUID.fromString("66666666-6666-6666-6666-666666666666"), money, period, "ERP");

		assertThat(price.money()).isEqualTo(money);
		assertThat(price.money().amount()).isEqualByComparingTo("10.50");
		assertThat(price.period()).isEqualTo(period);
		assertThat(price.sourceCode()).isEqualTo("ERP");
		assertThatThrownBy(() -> new Price(price.id(), money, period, "x".repeat(81)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Price(null, money, period, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Price(price.id(), null, period, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Price(price.id(), money, null, null)).isInstanceOf(NullPointerException.class);
	}
}
