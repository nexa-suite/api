package com.nexa.api.catalogcommercialpolicy.presentation.rest.mapper;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogItemDetailResponse;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogItemSummaryResponse;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogMediaResponse;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogPageResponse;
import com.nexa.api.catalogcommercialpolicy.presentation.rest.response.MoneyResponse;
import org.springframework.stereotype.Component;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingView;

@Component
public final class CatalogResponseMapper {
	public CatalogItemSummaryResponse toSummary(CatalogItemSummary item) {
		CatalogPricingView pricing = item.pricing();
		boolean buyerView = pricing != null && pricing.buyerView();
		return new CatalogItemSummaryResponse(item.catalogItemId(), item.productId(), item.itemName(), item.brandName(),
				item.categoryName(), item.presentation(), money(item.unitPriceAmount(), item.unitPriceCurrency()),
				item.coldChainRequirement(), new CatalogMediaResponse(item.imageUrl(), item.imageFileName()), item.status(),
				item.availabilityStatus(), item.nearExpiry(), item.promotionLabel(),
				pricing == null || buyerView ? null : money(pricing.basePrice(), pricing.currency()),
				pricing == null ? null : money(pricing.effectivePrice(), pricing.currency()),
				pricing == null ? null : money(pricing.discountAmount(), pricing.currency()),
				pricing == null ? null : pricing.currency(), pricing == null ? null : pricing.appliedPromotions().stream()
						.map(promotion -> new com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogAppliedPromotionResponse(
								promotion.id(), promotion.name(), promotion.discountType(), promotion.discountAmount())).toList(),
				pricing == null ? null : pricing.pricingAsOf(),
				item.productFamilyId(), item.productFamilyCode(), item.productFamilyName(), item.sellableSkuId(), item.skuCode(),
				item.unitOfMeasure(), item.packagingType(), item.netWeight(), item.grossWeight(), item.availabilityAsOf(),
				item.productVariantCode(), item.productVariantName(), item.sellableAvailability(),
				buyerView ? money(pricing.effectivePrice(), pricing.currency()) : null);
	}

	public CatalogItemDetailResponse toDetail(CatalogItemDetail item) {
		CatalogPricingView pricing = item.pricing();
		boolean buyerView = pricing != null && pricing.buyerView();
		return new CatalogItemDetailResponse(item.catalogItemId(), item.productId(), item.itemName(), item.brandName(),
				item.categoryName(), item.description(), item.presentation(), money(item.unitPriceAmount(), item.unitPriceCurrency()),
				item.coldChainRequirement(), new CatalogMediaResponse(item.imageUrl(), item.imageFileName()), item.status(),
				item.availabilityStatus(), item.nearExpiry(), item.promotionLabel(),
				pricing == null || buyerView ? null : money(pricing.basePrice(), pricing.currency()),
				pricing == null ? null : money(pricing.effectivePrice(), pricing.currency()),
				pricing == null ? null : money(pricing.discountAmount(), pricing.currency()),
				pricing == null ? null : pricing.currency(), pricing == null ? null : pricing.appliedPromotions().stream()
						.map(promotion -> new com.nexa.api.catalogcommercialpolicy.presentation.rest.response.CatalogAppliedPromotionResponse(
								promotion.id(), promotion.name(), promotion.discountType(), promotion.discountAmount())).toList(),
				pricing == null ? null : pricing.pricingAsOf(),
				item.productFamilyId(), item.productFamilyCode(), item.productFamilyName(), item.sellableSkuId(), item.skuCode(),
				item.unitOfMeasure(), item.packagingType(), item.netWeight(), item.grossWeight(), item.availabilityAsOf(),
				item.productVariantCode(), item.productVariantName(), item.sellableAvailability(),
				buyerView ? money(pricing.effectivePrice(), pricing.currency()) : null);
	}

	public CatalogPageResponse toPage(CatalogPage<CatalogItemSummary> page) {
		return new CatalogPageResponse(page.items().stream().map(this::toSummary).toList(), page.page(), page.size(),
				page.totalItems(), page.totalPages(), new CatalogPageResponse.SortResponse(page.sortField().wireValue(), page.sortDirection().wireValue()));
	}

	private static MoneyResponse money(java.math.BigDecimal amount, String currency) {
		return amount == null || currency == null ? null : new MoneyResponse(amount.toPlainString(), currency);
	}
}
