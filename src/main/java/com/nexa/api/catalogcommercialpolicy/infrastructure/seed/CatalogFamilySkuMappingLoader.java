package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Loads the reviewed, explicit legacy catalog-to-family/SKU mapping. */
@Component
public final class CatalogFamilySkuMappingLoader {
    private static final String RESOURCE_PATH = "seed/catalog/catalog-family-sku-mapping.v1.json";
    private final ObjectMapper objectMapper;

    public CatalogFamilySkuMappingLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, MappingItem> byLegacyCatalogItemId() {
        MappingDocument document = load();
        Map<String, MappingItem> mappings = document.items().stream()
                .collect(Collectors.toUnmodifiableMap(MappingItem::legacyCatalogItemId, Function.identity()));
        if (mappings.size() != 102 || document.items().size() != 102 || !new HashSet<>(mappings.keySet()).containsAll(expectedIds())) {
            throw new IllegalStateException("Catalog family/SKU mapping must contain CAT-0001 through CAT-0102");
        }
        List<MappingItem> provisional = document.items().stream()
                .filter(item -> Boolean.TRUE.equals(item.provisional())).toList();
        if (provisional.size() != 52
                || document.items().stream().filter(item -> !Boolean.TRUE.equals(item.provisional())).count() != 50) {
            throw new IllegalStateException("Catalog family/SKU mapping must preserve 50 curated and 52 provisional items");
        }
        for (int value = 51; value <= 102; value++) {
            String suffix = "%04d".formatted(value);
            MappingItem item = mappings.get("CAT-" + suffix);
            if (!Boolean.TRUE.equals(item.provisional()) || !item.legacyProductCode().equals("PROD-" + suffix)
                    || !item.skuCode().equals("PROD-" + suffix)
                    || !item.familyCode().equals("FAM-CAT-" + suffix)
                    || !"UNSPECIFIED".equals(item.presentation())) {
                throw new IllegalStateException("Provisional catalog mapping is inconsistent for CAT-" + suffix);
            }
        }
        return mappings;
    }

    private MappingDocument load() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
            try (var input = resource.getInputStream()) {
                MappingDocument document = objectMapper.readValue(input.readAllBytes(), MappingDocument.class);
                if (document == null || !"1.0".equals(document.schemaVersion())
                        || !"explicit-curated-with-provisional-reference".equals(document.mappingPolicy())
                        || document.items() == null) {
                    throw new IllegalStateException("Catalog family/SKU mapping metadata is invalid");
                }
                document.items().forEach(CatalogFamilySkuMappingLoader::validate);
                return document;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read catalog family/SKU mapping", exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Catalog family/SKU mapping is not valid JSON", exception);
        }
    }

    private static void validate(MappingItem item) {
        if (item == null || blank(item.legacyCatalogItemId()) || blank(item.legacyProductCode())
                || blank(item.familyCode()) || blank(item.familyName()) || blank(item.skuCode()) || blank(item.presentation())) {
            throw new IllegalStateException("Catalog family/SKU mapping contains an incomplete item");
        }
    }

    private static List<String> expectedIds() {
        return java.util.stream.IntStream.rangeClosed(1, 102)
                .mapToObj(value -> "CAT-%04d".formatted(value)).toList();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record MappingDocument(String schemaVersion, String source, String mappingPolicy,
                                  List<MappingItem> items) { }

    public record MappingItem(String legacyCatalogItemId, String legacyProductCode,
                              String familyCode, String familyName, String skuCode, String presentation,
                              Boolean provisional) { }
}
