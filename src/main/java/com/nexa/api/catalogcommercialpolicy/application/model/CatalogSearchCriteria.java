package com.nexa.api.catalogcommercialpolicy.application.model;

import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.ColdChainRequirement;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogInvariantViolation;


public record CatalogSearchCriteria(
		String query,
		String brand,
		String category,
		ColdChainRequirement coldChainRequirement,
		int page,
		int size,
		CatalogSortField sortField,
		SortDirection sortDirection) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	public static final int MAX_QUERY_LENGTH = 120;

	public CatalogSearchCriteria {
		query = normalizeQuery(query);
		brand = normalizeFilter(brand, "brand");
		category = normalizeFilter(category, "category");
		if (page < 0) throw new IllegalArgumentException("page must be greater than or equal to 0");
		if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("size must be between 1 and 100");
		sortField = sortField == null ? CatalogSortField.ITEM_NAME : sortField;
		sortDirection = sortDirection == null ? SortDirection.ASC : sortDirection;
	}

	public CatalogSearchCriteria() {
		this("", null, null, null, DEFAULT_PAGE, DEFAULT_SIZE, CatalogSortField.ITEM_NAME, SortDirection.ASC);
	}

	public static CatalogSearchCriteria fromWireValues(String query, String brand, String category, String coldChain,
			int page, int size, String sort, String direction) {
		ColdChainRequirement requirement;
		try {
			requirement = coldChain == null || coldChain.isBlank() ? null : ColdChainRequirement.fromLegacyValue(coldChain);
		} catch (CatalogInvariantViolation exception) {
			throw new IllegalArgumentException("Invalid cold-chain requirement", exception);
		}
		return new CatalogSearchCriteria(query, brand, category, requirement, page, size,
				CatalogSortField.fromWireValue(sort), SortDirection.fromWireValue(direction));
	}

	private static String normalizeQuery(String value) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("query exceeds 120 characters");
		return normalized;
	}

	private static String normalizeFilter(String value, String field) {
		if (value == null || value.isBlank()) return null;
		String normalized = value.trim();
		if (normalized.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException(field + " exceeds 120 characters");
		return normalized;
	}
}
