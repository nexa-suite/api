package com.nexa.api.catalogcommercialpolicy.application.model;

import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.ColdChainRequirement;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogInvariantViolation;

import java.util.UUID;

@org.springframework.modulith.NamedInterface("catalog-list-read")
public record CatalogSearchCriteria(
		String query,
		String brand,
		String category,
		ColdChainRequirement coldChainRequirement,
		int page,
		int size,
		CatalogSortField sortField,
		SortDirection sortDirection,
		boolean availableOnly,
		UUID familyId,
		UUID variantId,
		UUID brandId,
		UUID categoryId) {

	public static final int DEFAULT_PAGE = 0;
	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	public static final int MAX_QUERY_LENGTH = 120;

	public CatalogSearchCriteria {
		query = normalizeQuery(query);
		brand = normalizeFilter(brand, "brand");
		category = normalizeFilter(category, "category");
		if (brandId != null && brand != null) throw new IllegalArgumentException("Use either brandId or brand, not both");
		if (categoryId != null && category != null) throw new IllegalArgumentException("Use either categoryId or category, not both");
		if (page < 0) throw new IllegalArgumentException("page must be greater than or equal to 0");
		if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("size must be between 1 and 100");
		sortField = sortField == null ? CatalogSortField.ITEM_NAME : sortField;
		sortDirection = sortDirection == null ? SortDirection.ASC : sortDirection;
	}

	public CatalogSearchCriteria() {
		this("", null, null, null, DEFAULT_PAGE, DEFAULT_SIZE, CatalogSortField.ITEM_NAME, SortDirection.ASC,
				false, null, null, null, null);
	}

	public CatalogSearchCriteria(String query, String brand, String category, ColdChainRequirement coldChainRequirement,
			int page, int size, CatalogSortField sortField, SortDirection sortDirection, boolean availableOnly,
			UUID familyId, UUID variantId) {
		this(query, brand, category, coldChainRequirement, page, size, sortField, sortDirection, availableOnly,
				familyId, variantId, null, null);
	}

	public CatalogSearchCriteria(String query, String brand, String category, ColdChainRequirement coldChainRequirement,
			int page, int size, CatalogSortField sortField, SortDirection sortDirection, boolean availableOnly) {
		this(query, brand, category, coldChainRequirement, page, size, sortField, sortDirection, availableOnly, null, null);
	}

	public CatalogSearchCriteria(String query, String brand, String category,
			ColdChainRequirement coldChainRequirement, int page, int size,
			CatalogSortField sortField, SortDirection sortDirection) {
		this(query, brand, category, coldChainRequirement, page, size, sortField, sortDirection, false);
	}

	public CatalogSearchCriteria(String query, String brand, String category, ColdChainRequirement coldChainRequirement,
			int page, int size, CatalogSortField sortField, SortDirection sortDirection, UUID familyId, UUID variantId) {
		this(query, brand, category, coldChainRequirement, page, size, sortField, sortDirection, false, familyId, variantId);
	}

	public static CatalogSearchCriteria fromWireValues(String query, String brand, String category, String coldChain,
			int page, int size, String sort, String direction) {
		return fromWireValues(query, brand, category, coldChain, page, size, sort, direction, false);
	}

	public static CatalogSearchCriteria fromWireValues(String query, String brand, String category, String coldChain,
			int page, int size, String sort, String direction, boolean availableOnly) {
		return fromWireValues(query, brand, category, coldChain, page, size, sort, direction, availableOnly, null, null);
	}

	public static CatalogSearchCriteria fromWireValues(String query, String brand, String category, String coldChain,
			int page, int size, String sort, String direction, boolean availableOnly, UUID familyId, UUID variantId) {
		return fromWireValues(query, brand, category, coldChain, page, size, sort, direction,
				availableOnly, familyId, variantId, null, null);
	}

	public static CatalogSearchCriteria fromWireValues(String query, String brand, String category, String coldChain,
			int page, int size, String sort, String direction, boolean availableOnly, UUID familyId, UUID variantId,
			UUID brandId, UUID categoryId) {
		ColdChainRequirement requirement;
		try {
			requirement = coldChain == null || coldChain.isBlank() ? null : ColdChainRequirement.fromLegacyValue(coldChain);
		} catch (CatalogInvariantViolation exception) {
			throw new IllegalArgumentException("Invalid cold-chain requirement", exception);
		}
		return new CatalogSearchCriteria(query, brand, category, requirement, page, size,
				CatalogSortField.fromWireValue(sort), SortDirection.fromWireValue(direction), availableOnly,
				familyId, variantId, brandId, categoryId);
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
