package com.nexa.api.catalogmanagement.infrastructure.query;

import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItem;
import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;

public final class CatalogItemProjectionMapper {
	public CatalogItemSummary toSummary(CatalogItem item) {
		return new CatalogItemSummary(item.catalogItemId().value(), item.productId().value(), item.itemName().value(),
				item.brandName().value(), item.categoryName().value(), item.presentation().value(), item.unitPrice().amount(),
				item.unitPrice().currency().getCurrencyCode(), item.coldChainRequirement().name(), item.media().imageUrl(),
				item.media().imageFileName());
	}

	public CatalogItemDetail toDetail(CatalogItem item) {
		return new CatalogItemDetail(item.catalogItemId().value(), item.productId().value(), item.itemName().value(),
				item.brandName().value(), item.categoryName().value(), item.description().value(), item.presentation().value(),
				item.unitPrice().amount(), item.unitPrice().currency().getCurrencyCode(), item.coldChainRequirement().name(),
				item.media().imageUrl(), item.media().imageFileName());
	}
}
