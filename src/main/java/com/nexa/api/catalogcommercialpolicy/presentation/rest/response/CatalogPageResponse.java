package com.nexa.api.catalogcommercialpolicy.presentation.rest.response;

import java.util.List;

public record CatalogPageResponse(List<CatalogItemSummaryResponse> items, int page, int size, long totalItems,
		int totalPages, SortResponse sort) {
	public CatalogPageResponse { items = List.copyOf(items); }
	public record SortResponse(String field, String direction) { }
}
