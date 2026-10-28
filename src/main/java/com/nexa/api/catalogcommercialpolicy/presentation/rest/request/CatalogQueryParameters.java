package com.nexa.api.catalogcommercialpolicy.presentation.rest.request;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class CatalogQueryParameters {
	private static final String COLD_CHAIN_PATTERN = "(?i)(NONE|REFRIGERATED|FROZEN)";
	private static final String SORT_PATTERN = "(?i)(itemName|brandName|categoryName|unitPrice)";
	private static final String DIRECTION_PATTERN = "(?i)(asc|desc)";

	@Size(max = 120) private String q;
	@Size(max = 120) @Schema(description = "Exact case-insensitive brand name; prefer brandId from filter-options.") private String brand;
	@Size(max = 120) @Schema(description = "Exact case-insensitive category name; prefer categoryId from filter-options.") private String category;
	@Schema(description = "Exact brand ID returned by /api/v1/catalog-items/filter-options?kind=brand.") private UUID brandId;
	@Schema(description = "Exact category ID returned by /api/v1/catalog-items/filter-options?kind=category.") private UUID categoryId;
	private UUID familyId;
	private UUID variantId;
	@Pattern(regexp = COLD_CHAIN_PATTERN) private String coldChain;
	@Min(0) private int page = CatalogSearchCriteria.DEFAULT_PAGE;
	@Min(1) @Max(100) private int size = CatalogSearchCriteria.DEFAULT_SIZE;
	@Pattern(regexp = SORT_PATTERN) private String sort = "itemName";
	@Pattern(regexp = DIRECTION_PATTERN) private String direction = "asc";
	private boolean availableOnly;

	public CatalogSearchCriteria toCriteria() {
		return CatalogSearchCriteria.fromWireValues(normalized(q), normalized(brand), normalized(category), normalized(coldChain),
				page, size, normalized(sort), normalized(direction), availableOnly, familyId, variantId, brandId, categoryId);
	}

	public String getQ() { return q; }
	public void setQ(String q) { this.q = q; }
	public String getBrand() { return brand; }
	public void setBrand(String brand) { this.brand = brand; }
	public String getCategory() { return category; }
	public void setCategory(String category) { this.category = category; }
	public UUID getBrandId() { return brandId; }
	public void setBrandId(UUID brandId) { this.brandId = brandId; }
	public UUID getCategoryId() { return categoryId; }
	public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
	public UUID getFamilyId() { return familyId; }
	public void setFamilyId(UUID familyId) { this.familyId = familyId; }
	public UUID getVariantId() { return variantId; }
	public void setVariantId(UUID variantId) { this.variantId = variantId; }
	public String getColdChain() { return coldChain; }
	public void setColdChain(String coldChain) { this.coldChain = coldChain; }
	public int getPage() { return page; }
	public void setPage(int page) { this.page = page; }
	public int getSize() { return size; }
	public void setSize(int size) { this.size = size; }
	public String getSort() { return sort; }
	public void setSort(String sort) { this.sort = sort; }
	public String getDirection() { return direction; }
	public void setDirection(String direction) { this.direction = direction; }
	public boolean isAvailableOnly() { return availableOnly; }
	public void setAvailableOnly(boolean availableOnly) { this.availableOnly = availableOnly; }

	private static String normalized(String value) {
		if (value == null) return null;
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
