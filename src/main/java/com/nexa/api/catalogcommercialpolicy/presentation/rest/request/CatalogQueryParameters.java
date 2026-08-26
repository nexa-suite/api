package com.nexa.api.catalogcommercialpolicy.presentation.rest.request;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CatalogQueryParameters {
	private static final String COLD_CHAIN_PATTERN = "(?i)(NONE|REFRIGERATED|FROZEN)";
	private static final String SORT_PATTERN = "(?i)(itemName|brandName|categoryName|unitPrice)";
	private static final String DIRECTION_PATTERN = "(?i)(asc|desc)";

	@Size(max = 120) private String q;
	@Size(max = 120) private String brand;
	@Size(max = 120) private String category;
	@Pattern(regexp = COLD_CHAIN_PATTERN) private String coldChain;
	@Min(0) private int page = CatalogSearchCriteria.DEFAULT_PAGE;
	@Min(1) @Max(100) private int size = CatalogSearchCriteria.DEFAULT_SIZE;
	@Pattern(regexp = SORT_PATTERN) private String sort = "itemName";
	@Pattern(regexp = DIRECTION_PATTERN) private String direction = "asc";

	public CatalogSearchCriteria toCriteria() {
		return CatalogSearchCriteria.fromWireValues(normalized(q), normalized(brand), normalized(category), normalized(coldChain),
				page, size, normalized(sort), normalized(direction));
	}

	public String getQ() { return q; }
	public void setQ(String q) { this.q = q; }
	public String getBrand() { return brand; }
	public void setBrand(String brand) { this.brand = brand; }
	public String getCategory() { return category; }
	public void setCategory(String category) { this.category = category; }
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

	private static String normalized(String value) {
		if (value == null) return null;
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
