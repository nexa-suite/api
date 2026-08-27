package com.nexa.api.catalogcommercialpolicy.application.model;

import java.time.Instant;
import java.util.List;

public final class CatalogVariantModels {
    private CatalogVariantModels() { }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page { items = List.copyOf(items); }
    }

    public record VariantView(String id, String familyId, String familyCode, String familyName,
                              String code, String name, String description, String status,
                              long skuCount, long version, Instant createdAt, Instant updatedAt) { }
}
