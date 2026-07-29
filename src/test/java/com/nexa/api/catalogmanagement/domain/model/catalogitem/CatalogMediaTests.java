package com.nexa.api.catalogmanagement.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogMediaTests {
	@Test
	void acceptsSafeRelativeCatalogMedia() {
		CatalogMedia media = new CatalogMedia(" /catalog-items/cheese.jpeg ", " cheese.jpeg ");
		assertThat(media.imageUrl()).isEqualTo("/catalog-items/cheese.jpeg");
		assertThat(media.imageFileName()).isEqualTo("cheese.jpeg");
	}

	@Test
	void rejectsUnsafePathsAndMismatches() {
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items/../secret.png", "secret.png")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogMedia("https://example.test/secret.png", "secret.png")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items/item.png?raw=1", "item.png")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items/item.png#part", "item.png")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items\\item.png", "item.png")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items/other.png", "item.png")).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items/item.gif", "item.gif")).isInstanceOf(CatalogInvariantViolation.class);
	}

	@Test
	void rejectsOverlongUrl() {
		String filename = "a".repeat(230) + ".png";
		assertThatThrownBy(() -> new CatalogMedia("/catalog-items/" + filename, filename)).isInstanceOf(CatalogInvariantViolation.class);
	}
}
