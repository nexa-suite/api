package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItem;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CatalogSeedDomainMappingTests {
	@Autowired
	private CatalogSeedLoader loader;

	@Test
	void mapsAllRecordsInSeedOrderAndPreservesDomainValues() {
		List<CatalogItem> items = loader.loadDomainCatalog();

		assertThat(items).hasSize(50);
		assertThat(items.get(0).catalogItemId().value()).isEqualTo("CAT-0001");
		assertThat(items.get(0).productId().value()).isEqualTo("PROD-0001");
		assertThat(items.get(0).itemName().value()).isEqualTo("QUESO GRANA PADANO DOP 150G");
		assertThat(items.get(0).unitPrice().amount()).isEqualByComparingTo("17.3");
		assertThat(items.get(0).unitPrice().currency().getCurrencyCode()).isEqualTo("PEN");
		assertThat(items.get(0).media().imageUrl()).isEqualTo("/catalog-items/agriform-queso-grana-padano-dop-150g.png");
		assertThat(items.get(0).presentation().value()).isEqualTo("150G");
		assertThat(items).allSatisfy(item -> assertThat(item.status()).isEqualTo(CatalogItemStatus.ACTIVE));
	}

	@Test
	void returnsImmutableDomainListAndExcludesImportOnlyState() {
		List<CatalogItem> items = loader.loadDomainCatalog();
		assertThatThrownBy(items::clear).isInstanceOf(UnsupportedOperationException.class);

		assertThat(Stream.of(CatalogItem.class.getDeclaredFields()).map(Field::getName))
				.doesNotContain("availableStock", "sourcePriceCode", "sourcePriceDescription", "catalogSeedItemRecord");
		assertThat(Stream.of(CatalogItem.class.getDeclaredFields()).map(Field::getType)
				.anyMatch(type -> type.equals(CatalogSeedItemRecord.class))).isFalse();
	}
}
