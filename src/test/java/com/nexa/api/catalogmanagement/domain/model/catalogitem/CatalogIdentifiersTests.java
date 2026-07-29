package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogIdentifiersTests {
	@Test
	void trimsUppercasesAndPrintsCatalogIdentifier() {
		CatalogItemId id = new CatalogItemId("  cat-ab-01  ");
		assertThat(id.value()).isEqualTo("CAT-AB-01");
		assertThat(id.toString()).isEqualTo("CAT-AB-01");
		assertThat(id).isEqualTo(new CatalogItemId("CAT-AB-01"));
	}

	@Test
	void validatesCatalogIdentifierPrefixLengthSpacesAndCharacters() {
		assertThatThrownBy(() -> new CatalogItemId(null)).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogItemId("ITEM-0001")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogItemId("CAT-00 01")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogItemId("CAT-00_01")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogItemId("CAT-" + "A".repeat(61))).isInstanceOf(CatalogInvariantViolation.class);
	}

	@Test
	void validatesProductIdentifier() {
		assertThat(new ProductId(" prod-ab-01 ").value()).isEqualTo("PROD-AB-01");
		assertThatThrownBy(() -> new ProductId("CAT-0001")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new ProductId("PROD-00 01")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new ProductId("PROD-" + "A".repeat(61))).isInstanceOf(CatalogInvariantViolation.class);
	}
}
