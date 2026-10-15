package com.nexa.api.catalogcommercialpolicy.application.model;

import java.util.List;

/** Safe taxonomy choices drawn from the caller's visible catalog. */
public record CatalogFilterOptions(List<Option> items, int page, int size, long total) {
    public CatalogFilterOptions { items = List.copyOf(items); }
    public record Option(String id, String name) { }
}
