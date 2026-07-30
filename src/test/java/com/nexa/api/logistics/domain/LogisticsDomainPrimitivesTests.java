package com.nexa.api.logistics.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticsDomainPrimitivesTests {
	private static final Instant START = Instant.parse("2026-07-28T10:00:00Z");

	@Test
	void identifiersNormalize() {
		assertThat(new ShipmentId(" shp-01 ").value()).isEqualTo("SHP-01");
		assertThat(new DispatchOrderId("dispatch-01").toString()).isEqualTo("DISPATCH-01");
	}

	@Test
	void temperatureReadingKeepsExactValueAndExplicitUnit() {
		TemperatureReading reading = new TemperatureReading(new BigDecimal("-18.250"), "c", START);

		assertThat(reading.value()).isEqualByComparingTo("-18.250");
		assertThat(reading.value().scale()).isEqualTo(3);
		assertThat(reading.unit()).isEqualTo(TemperatureUnit.CELSIUS);
		assertThat(reading.recordedAt()).isEqualTo(START);
	}

	@Test
	void deliveryWindowRequiresOrderedNonEmptyBounds() {
		DeliveryWindow window = new DeliveryWindow(START, START.plusSeconds(30));

		assertThat(window.startsAt()).isEqualTo(START);
		assertThat(window.endsAt()).isEqualTo(START.plusSeconds(30));
		assertThatThrownBy(() -> new DeliveryWindow(START, START)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeliveryWindow(null, START)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsIncompleteTemperatureReadingsAndUnknownUnits() {
		assertThatThrownBy(() -> new TemperatureReading(null, TemperatureUnit.CELSIUS, START))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TemperatureReading(BigDecimal.ONE, (TemperatureUnit) null, START))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TemperatureReading(BigDecimal.ONE, "KELVIN", START))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TemperatureReading(BigDecimal.ONE, TemperatureUnit.CELSIUS, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesShipmentStatusVocabulary() {
		assertThat(ShipmentStatus.values()).containsExactly(
			ShipmentStatus.PLANNED,
			ShipmentStatus.DISPATCHED,
			ShipmentStatus.IN_TRANSIT,
			ShipmentStatus.DELIVERED,
			ShipmentStatus.FAILED,
			ShipmentStatus.CANCELLED);
	}
}
