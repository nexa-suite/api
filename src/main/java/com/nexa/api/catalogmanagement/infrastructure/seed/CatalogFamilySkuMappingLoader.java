package com.nexa.api.catalogmanagement.infrastructure.seed;

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
        if (mappings.size() != 50 || !new HashSet<>(mappings.keySet()).containsAll(expectedIds())) {
            throw new IllegalStateException("Catalog family/SKU mapping must contain CAT-0001 through CAT-0050");
        }
        return mappings;
    }

    private MappingDocument load() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
            try (var input = resource.getInputStream()) {
                MappingDocument document = objectMapper.readValue(input.readAllBytes(), MappingDocument.class);
                if (document == null || !"1.0".equals(document.schemaVersion())
                        || !"explicit-curated".equals(document.mappingPolicy())
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
                || blank(item.familyCode()) || blank(item.skuCode()) || blank(item.presentation())) {
            throw new IllegalStateException("Catalog family/SKU mapping contains an incomplete item");
        }
    }

    private static List<String> expectedIds() {
        return java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(value -> "CAT-%04d".formatted(value)).toList();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record MappingDocument(String schemaVersion, String source, String mappingPolicy,
                                  List<MappingItem> items) { }

    public record MappingItem(String legacyCatalogItemId, String legacyProductCode,
                              String familyCode, String skuCode, String presentation) { }
}
