package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogNamesTests {
	@Test
	void trimsAndPreservesNameCapitalization() {
		assertThat(new ItemName("  Mixed Case  ").value()).isEqualTo("Mixed Case");
		assertThat(new BrandName("  Brand Co  ").value()).isEqualTo("Brand Co");
		assertThat(new CategoryName("  Cheese  ").value()).isEqualTo("Cheese");
		assertThat(new CatalogDescription("  Description  ").value()).isEqualTo("Description");
		assertThat(new ProductPresentation("  BOX 150G  ").value()).isEqualTo("BOX 150G");
	}

	@Test
	void rejectsBlankValues() {
		assertThatThrownBy(() -> new ItemName(" ")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new BrandName(null)).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CategoryName("\t")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogDescription("")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new ProductPresentation(null)).isInstanceOf(CatalogInvariantViolation.class);
	}

	@Test
	void enforcesHistoricalMaximums() {
		assertThatThrownBy(() -> new ItemName("x".repeat(161))).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new BrandName("x".repeat(121))).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CategoryName("x".repeat(81))).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogDescription("x".repeat(501))).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new ProductPresentation("x".repeat(161))).isInstanceOf(CatalogInvariantViolation.class);
		assertThat(new BrandName("Brand")).isEqualTo(new BrandName("Brand"));
	}
}
