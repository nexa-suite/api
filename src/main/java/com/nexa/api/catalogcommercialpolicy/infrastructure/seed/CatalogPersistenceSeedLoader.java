package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Loads the reviewed 102-item persistence seed without changing the v1 query seed. */
@Component
public final class CatalogPersistenceSeedLoader {
    private static final String RESOURCE_PATH = "seed/catalog/catalog-items.v2.json";
    private final ObjectMapper objectMapper;

    public CatalogPersistenceSeedLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<CatalogPersistenceSeedItemRecord> load() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
            byte[] rawContent;
            try (var inputStream = resource.getInputStream()) {
                rawContent = inputStream.readAllBytes();
            }
            List<CatalogPersistenceSeedItemRecord> items = objectMapper.readValue(rawContent, new TypeReference<>() { });
            CatalogPersistenceSeedValidator.validate(items, rawContent);
            return List.copyOf(items);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read catalog persistence seed resource", exception);
        }
    }
}
