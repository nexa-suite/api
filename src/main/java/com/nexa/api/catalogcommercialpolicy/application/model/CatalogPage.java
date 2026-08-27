package com.nexa.api.catalogcommercialpolicy.application.model;

import java.util.List;

public record CatalogPage<T>(
		List<T> items,
		int page,
		int size,
		long totalItems,
		int totalPages,
		CatalogSortField sortField,
		SortDirection sortDirection) {

	public CatalogPage {
		items = List.copyOf(items);
		if (page < 0 || size < 1 || totalItems < 0 || totalPages < 0) {
			throw new IllegalArgumentException("Invalid catalog page metadata");
		}
		if (sortField == null || sortDirection == null) throw new IllegalArgumentException("Catalog sorting is required");
	}

	public CatalogPage(List<T> items, int page, int size, long totalItems,
			CatalogSortField sortField, SortDirection sortDirection) {
		this(items, page, size, totalItems, totalPages(totalItems, size), sortField, sortDirection);
	}

	private static int totalPages(long totalItems, int size) {
		long pages = (totalItems + size - 1) / size;
		return pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages;
	}
}
