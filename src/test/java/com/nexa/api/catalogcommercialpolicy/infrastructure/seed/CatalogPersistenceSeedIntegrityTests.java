package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CatalogPersistenceSeedIntegrityTests {
    @Autowired
    private CatalogPersistenceSeedLoader persistenceLoader;

    @Autowired
    private CatalogSeedLoader legacyLoader;

    @Autowired
    private CatalogFamilySkuMappingLoader mappingLoader;

    @Test
    void loadsTheCanonicalPersistenceSeedWithExplicitProvisionalBoundary() {
        List<CatalogPersistenceSeedItemRecord> items = persistenceLoader.load();

        assertThat(items).hasSize(CatalogPersistenceSeedValidator.EXPECTED_COUNT);
        assertThat(items.subList(0, CatalogPersistenceSeedValidator.EXPECTED_CURATED_COUNT))
                .allSatisfy(item -> {
                    assertThat(item.buyerVisible()).isTrue();
                    assertThat(item.provisionalReference()).isFalse();
                });
        assertThat(items.subList(CatalogPersistenceSeedValidator.EXPECTED_CURATED_COUNT,
                CatalogPersistenceSeedValidator.EXPECTED_COUNT))
                .allSatisfy(item -> {
                    assertThat(item.buyerVisible()).isFalse();
                    assertThat(item.provisionalReference()).isTrue();
                    assertThat(item.sourcePriceCode()).isEqualTo("PROVISIONAL_REFERENCE");
                });
        assertThat(items).allSatisfy(item -> {
            assertThat(item.imageUrl()).isEqualTo("/catalog-items/" + item.imageFileName());
            assertThat(item.unitPriceCurrency()).isEqualTo("PEN");
            assertThat(item.coldChainRequirement()).isEqualTo("Refrigerated");
        });
        assertThat(items.stream().map(CatalogPersistenceSeedItemRecord::catalogItemId))
                .containsExactlyElementsOf(expectedIds("CAT-"));
        assertThat(items.stream().map(CatalogPersistenceSeedItemRecord::productId))
                .containsExactlyElementsOf(expectedIds("PROD-"));
        assertThat(items.stream().map(CatalogPersistenceSeedItemRecord::sourcePriceCode).filter("PROVISIONAL_REFERENCE"::equals))
                .hasSize(CatalogPersistenceSeedValidator.EXPECTED_PROVISIONAL_COUNT);
    }

    @Test
    void preservesEveryFrozenV1CommercialFieldForTheFirstFiftyItems() {
        Map<String, CatalogSeedItemRecord> legacyById = legacyLoader.load().stream()
                .collect(java.util.stream.Collectors.toMap(CatalogSeedItemRecord::catalogItemId, item -> item));

        persistenceLoader.load().subList(0, CatalogPersistenceSeedValidator.EXPECTED_CURATED_COUNT)
                .forEach(item -> {
                    CatalogSeedItemRecord legacy = legacyById.get(item.catalogItemId());
                    assertThat(legacy).as(item.catalogItemId()).isNotNull();
                    assertThat(item.productId()).isEqualTo(legacy.productId());
                    assertThat(item.itemName()).isEqualTo(legacy.itemName());
                    assertThat(item.brandName()).isEqualTo(legacy.brandName());
                    assertThat(item.categoryName()).isEqualTo(legacy.categoryName());
                    assertThat(item.description()).isEqualTo(legacy.description());
                    assertThat(item.unitPriceAmount()).isEqualByComparingTo(legacy.unitPriceAmount());
                    assertThat(item.unitPriceCurrency()).isEqualTo(legacy.unitPriceCurrency());
                    assertThat(item.coldChainRequirement()).isEqualTo(legacy.coldChainRequirement());
                    assertThat(item.imageUrl()).isEqualTo(legacy.imageUrl());
                    assertThat(item.imageFileName()).isEqualTo(legacy.imageFileName());
                    assertThat(item.presentation()).isEqualTo(legacy.presentation());
                    assertThat(item.sourcePriceCode()).isEqualTo(legacy.sourcePriceCode());
                    assertThat(item.sourcePriceDescription()).isEqualTo(legacy.sourcePriceDescription());
                });
    }

    @Test
    void coversTheExplicitFamilySkuMappingWithoutInventingStockOrGtin() {
        List<CatalogPersistenceSeedItemRecord> items = persistenceLoader.load();
        Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings = mappingLoader.byLegacyCatalogItemId();

        assertThat(mappings.keySet()).containsExactlyInAnyOrderElementsOf(
                items.stream().map(CatalogPersistenceSeedItemRecord::catalogItemId).toList());
        items.forEach(item -> {
            CatalogFamilySkuMappingLoader.MappingItem mapping = mappings.get(item.catalogItemId());
            assertThat(mapping.legacyProductCode()).isEqualTo(item.productId());
            assertThat(mapping.skuCode()).isEqualTo(item.productId());
            assertThat(mapping.presentation()).isEqualTo(item.presentation());
            assertThat(Boolean.TRUE.equals(mapping.provisional())).isEqualTo(item.provisionalReference());
        });
        assertThat(CatalogPersistenceSeedItemRecord.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("availableStock", "gtin");
    }

    @Test
    void returnsAnImmutablePersistenceSeed() {
        List<CatalogPersistenceSeedItemRecord> items = persistenceLoader.load();

        assertThatThrownBy(items::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<String> expectedIds(String prefix) {
        return java.util.stream.IntStream.rangeClosed(1, CatalogPersistenceSeedValidator.EXPECTED_COUNT)
                .mapToObj(value -> prefix + "%04d".formatted(value))
                .toList();
    }
}
