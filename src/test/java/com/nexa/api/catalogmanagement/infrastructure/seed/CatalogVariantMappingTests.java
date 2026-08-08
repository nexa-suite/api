package com.nexa.api.catalogmanagement.infrastructure.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CatalogVariantMappingTests {
    @Autowired
    private CatalogFamilySkuMappingLoader loader;

    @Autowired
    private CatalogVariantMappingLoader variantLoader;

    @Test
    void mapsAllFiftySeedSkusToAnExplicitCuratedFamily() {
        Map<String, CatalogFamilySkuMappingLoader.MappingItem> mapping = loader.byLegacyCatalogItemId();

        assertThat(mapping).hasSize(50);
        assertThat(mapping.values().stream().map(CatalogFamilySkuMappingLoader.MappingItem::familyCode).distinct())
                .hasSize(36);
        assertThat(mapping.values().stream().map(CatalogFamilySkuMappingLoader.MappingItem::skuCode).distinct())
                .hasSize(50);
        assertThat(mapping.values()).allSatisfy(item -> assertThat(item.familyName()).isNotBlank());
    }

    @Test
    void keepsCommercialVariantsTogetherAndCommerciallyDifferentRecipesOrMaturationApart() {
        Map<String, CatalogFamilySkuMappingLoader.MappingItem> mapping = loader.byLegacyCatalogItemId();

        assertSameFamily(mapping, "CAT-0005", "CAT-0006");
        assertSameFamily(mapping, "CAT-0012", "CAT-0013");
        assertSameFamily(mapping, "CAT-0014", "CAT-0015");
        assertSameFamily(mapping, "CAT-0016", "CAT-0017");
        assertSameFamily(mapping, "CAT-0018", "CAT-0019");
        assertSameFamily(mapping, "CAT-0020", "CAT-0021");
        assertSameFamily(mapping, "CAT-0022", "CAT-0023");
        assertSameFamily(mapping, "CAT-0024", "CAT-0025");
        assertSameFamily(mapping, "CAT-0026", "CAT-0027");
        assertSameFamily(mapping, "CAT-0028", "CAT-0029");
        assertSameFamily(mapping, "CAT-0045", "CAT-0046");
        assertSameFamily(mapping, "CAT-0047", "CAT-0048");

        assertThat(mapping.get("CAT-0005").familyCode()).isNotEqualTo(mapping.get("CAT-0007").familyCode());
        assertThat(mapping.get("CAT-0033").familyCode()).isNotEqualTo(mapping.get("CAT-0034").familyCode());
        assertThat(mapping.get("CAT-0044").familyCode()).isNotEqualTo(mapping.get("CAT-0045").familyCode());
        assertThat(mapping.get("CAT-0045").familyCode()).isNotEqualTo(mapping.get("CAT-0047").familyCode());
    }

    @Test
    void exposesOnlyTheReviewedExplicitGoudaVariantMapping() {
        Map<String, CatalogVariantMappingLoader.MappingItem> mapping = variantLoader.byLegacyCatalogItemId();

        assertThat(mapping).hasSize(12);
        assertThat(mapping.values().stream().map(CatalogVariantMappingLoader.MappingItem::variantCode).distinct())
                .containsExactlyInAnyOrder("VAR-GOUDA-CABRA", "VAR-GOUDA-CHILI", "VAR-GOUDA-COMINO",
                        "VAR-GOUDA-FINAS-HIERBAS", "VAR-GOUDA-NATURAL", "VAR-GOUDA-PIMIENTA");
        assertThat(mapping.values()).allSatisfy(item -> {
            assertThat(item.familyCode()).isEqualTo("FAM-GOUDA");
            assertThat(item.familyName()).isEqualTo("QUESO GOUDA");
            assertThat(item.variantName()).isNotBlank();
        });
    }

    private static void assertSameFamily(Map<String, CatalogFamilySkuMappingLoader.MappingItem> mapping,
            String first, String second) {
        assertThat(mapping.get(first).familyCode()).isEqualTo(mapping.get(second).familyCode());
        assertThat(mapping.get(first).familyName()).isEqualTo(mapping.get(second).familyName());
    }
}
