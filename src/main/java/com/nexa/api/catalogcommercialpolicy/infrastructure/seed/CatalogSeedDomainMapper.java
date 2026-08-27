package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.BrandName;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogDescription;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItem;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogMedia;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CategoryName;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.ColdChainRequirement;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.ItemName;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.Money;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.ProductId;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.ProductPresentation;

import java.util.Currency;

public final class CatalogSeedDomainMapper {
	public CatalogItem map(CatalogSeedItemRecord record) {
		if (record == null) throw new CatalogSeedIntegrityException("cannot map a null record");
		return CatalogItem.create(
				new CatalogItemId(record.catalogItemId()),
				new ProductId(record.productId()),
				new ItemName(record.itemName()),
				new BrandName(record.brandName()),
				new CategoryName(record.categoryName()),
				new CatalogDescription(record.description()),
				new ProductPresentation(record.presentation()),
				new Money(record.unitPriceAmount(), Currency.getInstance(record.unitPriceCurrency())),
				ColdChainRequirement.fromLegacyValue(record.coldChainRequirement()),
				new CatalogMedia(record.imageUrl(), record.imageFileName()));
	}
}
