package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItem;
import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.infrastructure.seed.CatalogFamilySkuMappingLoader;

import java.time.Instant;
import java.util.Map;

public final class CatalogItemProjectionMapper {
	private final Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappingByCatalogItemId;

	public CatalogItemProjectionMapper() {
		this.mappingByCatalogItemId = Map.of();
	}

	public CatalogItemProjectionMapper(CatalogFamilySkuMappingLoader mappingLoader) {
		this.mappingByCatalogItemId = mappingLoader == null ? Map.of() : mappingLoader.byLegacyCatalogItemId();
	}

	public CatalogItemSummary toSummary(CatalogItem item) {
		CatalogFamilySkuMappingLoader.MappingItem mapping = mappingByCatalogItemId.get(item.catalogItemId().value());
		return new CatalogItemSummary(item.catalogItemId().value(), item.productId().value(), item.itemName().value(),
				item.brandName().value(), item.categoryName().value(), item.presentation().value(), item.unitPrice().amount(),
				item.unitPrice().currency().getCurrencyCode(), item.coldChainRequirement().name(), item.media().imageUrl(),
				item.media().imageFileName(), item.status().name(), "UNKNOWN", false, null,
				com.nexa.api.catalogmanagement.application.model.CatalogPricingView.base(item.unitPrice().amount(),
						item.unitPrice().currency().getCurrencyCode(), Instant.EPOCH), null,
				mapping == null ? null : mapping.familyCode(), mapping == null ? null : mapping.familyName(), item.productId().value(),
				mapping == null ? item.productId().value() : mapping.skuCode(), "UNIT", null, null, null, Instant.EPOCH);
	}

	public CatalogItemDetail toDetail(CatalogItem item) {
		CatalogFamilySkuMappingLoader.MappingItem mapping = mappingByCatalogItemId.get(item.catalogItemId().value());
		return new CatalogItemDetail(item.catalogItemId().value(), item.productId().value(), item.itemName().value(),
				item.brandName().value(), item.categoryName().value(), item.description().value(), item.presentation().value(),
				item.unitPrice().amount(), item.unitPrice().currency().getCurrencyCode(), item.coldChainRequirement().name(),
				item.media().imageUrl(), item.media().imageFileName(), item.status().name(), "UNKNOWN", false, null,
				com.nexa.api.catalogmanagement.application.model.CatalogPricingView.base(item.unitPrice().amount(),
						item.unitPrice().currency().getCurrencyCode(), Instant.EPOCH), null,
				mapping == null ? null : mapping.familyCode(), mapping == null ? null : mapping.familyName(), item.productId().value(),
				mapping == null ? item.productId().value() : mapping.skuCode(), "UNIT", null, null, null, Instant.EPOCH);
	}
}
