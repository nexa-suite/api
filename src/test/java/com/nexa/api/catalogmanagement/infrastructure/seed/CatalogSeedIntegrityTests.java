package com.nexa.api.catalogmanagement.infrastructure.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CatalogSeedIntegrityTests {
	@Autowired
	private CatalogSeedLoader loader;

	@Test
	void loadsTheCanonicalSeedWithExpectedShape() {
		List<CatalogSeedItemRecord> items = loader.load();
		assertThat(items).hasSize(50);
		assertThat(items).allSatisfy(item -> {
			assertThat(item.catalogItemId()).isNotBlank();
			assertThat(item.productId()).isNotBlank();
			assertThat(item.imageFileName()).isNotBlank();
			assertThat(item.imageUrl()).isEqualTo("/catalog-items/" + item.imageFileName());
			assertThat(item.unitPriceCurrency()).isEqualTo("PEN");
			assertThat(item.coldChainRequirement()).isEqualTo("Refrigerated");
			assertThat(item.unitPriceAmount()).isNotNegative();
			assertThat(item.availableStock()).isGreaterThanOrEqualTo(0);
		});
	}

	@Test
	void returnsAnImmutableListAndAllowsSourcePriceCodeDuplicates() {
		List<CatalogSeedItemRecord> items = loader.load();
		assertThatThrownBy(items::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThat(items.stream().map(CatalogSeedItemRecord::sourcePriceCode).distinct()).hasSizeLessThan(items.size());
	}
}
