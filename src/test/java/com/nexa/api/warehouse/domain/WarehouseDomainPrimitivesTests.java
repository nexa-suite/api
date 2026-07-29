package com.nexa.api.warehouse.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarehouseDomainPrimitivesTests {
	@Test
	void identifiersNormalizeAndRemainDistinctTypes() {
		assertThat(new WarehouseId(" wh-01 ").value()).isEqualTo("WH-01");
		assertThat(new InventoryItemId("item-01").toString()).isEqualTo("ITEM-01");
		assertThat(new InventoryLotId(" lot-01 ")).isEqualTo(new InventoryLotId("LOT-01"));
	}

	@Test
	void quantityIsExactNonNegativeAndUnitExplicit() {
		Quantity quantity = new Quantity(new BigDecimal("12.500"), " kg ");

		assertThat(quantity.value()).isEqualByComparingTo("12.500");
		assertThat(quantity.value().scale()).isEqualTo(3);
		assertThat(quantity.unit()).isEqualTo("KG");
		assertThat(Quantity.of(BigDecimal.ZERO, "EACH").value()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void rejectsInvalidIdentifiersAndQuantities() {
		assertThatThrownBy(() -> new WarehouseId("WH_01")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InventoryItemId(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InventoryLotId("A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Quantity(new BigDecimal("-0.001"), "KG"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Quantity(BigDecimal.ONE, " ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesSupportedStorageAndInventoryVocabulary() {
		assertThat(StorageCondition.values()).containsExactly(
			StorageCondition.AMBIENT,
			StorageCondition.REFRIGERATED,
			StorageCondition.FROZEN);
		assertThat(InventoryStatus.values()).containsExactly(
			InventoryStatus.AVAILABLE,
			InventoryStatus.RESERVED,
			InventoryStatus.QUARANTINED,
			InventoryStatus.EXPIRED,
			InventoryStatus.DEPLETED);
	}
}
