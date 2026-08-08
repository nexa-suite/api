package com.nexa.api.catalogmanagement.infrastructure.seed;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Loads the reviewed V1 family-to-variant consolidation without name heuristics. */
@Component
public final class CatalogVariantMappingLoader {
    private static final String RESOURCE_PATH = "seed/catalog/catalog-variant-mapping.v1.json";
    private final ObjectMapper objectMapper;

    public CatalogVariantMappingLoader(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Map<String, MappingItem> byLegacyCatalogItemId() {
        MappingDocument document = load();
        return document.items().stream().collect(Collectors.toUnmodifiableMap(MappingItem::legacyCatalogItemId, Function.identity()));
    }

    private MappingDocument load() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
            try (var input = resource.getInputStream()) {
                MappingDocument document = objectMapper.readValue(input.readAllBytes(), MappingDocument.class);
                if (document == null || !"1.0".equals(document.schemaVersion())
                        || !"explicit-curated".equals(document.mappingPolicy()) || document.items() == null
                        || document.items().size() != 12) {
                    throw new IllegalStateException("Catalog variant mapping metadata is invalid");
                }
                document.items().forEach(CatalogVariantMappingLoader::validate);
                return document;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read catalog variant mapping", exception);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Catalog variant mapping is not valid JSON", exception);
        }
    }

    private static void validate(MappingItem item) {
        if (item == null || blank(item.legacyCatalogItemId()) || blank(item.familyCode())
                || blank(item.familyName()) || blank(item.variantCode()) || blank(item.variantName())) {
            throw new IllegalStateException("Catalog variant mapping contains an incomplete item");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record MappingDocument(String schemaVersion, String source, String mappingPolicy,
                                  List<MappingItem> items) { }

    public record MappingItem(String legacyCatalogItemId, String familyCode, String familyName,
                              String variantCode, String variantName) { }
}
