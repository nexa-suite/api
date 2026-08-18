package com.nexa.api.catalogmanagement.presentation.rest.mapper;

import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogItemSummary;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.presentation.rest.response.CatalogItemDetailResponse;
import com.nexa.api.catalogmanagement.presentation.rest.response.CatalogItemSummaryResponse;
import com.nexa.api.catalogmanagement.presentation.rest.response.CatalogMediaResponse;
import com.nexa.api.catalogmanagement.presentation.rest.response.CatalogPageResponse;
import com.nexa.api.catalogmanagement.presentation.rest.response.MoneyResponse;
import org.springframework.stereotype.Component;

@Component
public final class CatalogResponseMapper {
	public CatalogItemSummaryResponse toSummary(CatalogItemSummary item) {
		return new CatalogItemSummaryResponse(item.catalogItemId(), item.productId(), item.itemName(), item.brandName(),
				item.categoryName(), item.presentation(), money(item.unitPriceAmount(), item.unitPriceCurrency()),
				item.coldChainRequirement(), new CatalogMediaResponse(item.imageUrl(), item.imageFileName()), item.status(),
				item.availabilityStatus(), item.nearExpiry(), item.promotionLabel(), money(item.pricing().basePrice(), item.pricing().currency()),
				money(item.pricing().effectivePrice(), item.pricing().currency()), money(item.pricing().discountAmount(), item.pricing().currency()),
				item.pricing().currency(), item.pricing().appliedPromotions().stream()
						.map(promotion -> new com.nexa.api.catalogmanagement.presentation.rest.response.CatalogAppliedPromotionResponse(
								promotion.id(), promotion.name(), promotion.discountType(), promotion.discountAmount())).toList(), item.pricing().pricingAsOf(),
				item.productFamilyId(), item.productFamilyCode(), item.productFamilyName(), item.sellableSkuId(), item.skuCode(),
				item.unitOfMeasure(), item.packagingType(), item.netWeight(), item.grossWeight(), item.availabilityAsOf(),
				item.productVariantCode(), item.productVariantName());
	}

	public CatalogItemDetailResponse toDetail(CatalogItemDetail item) {
		return new CatalogItemDetailResponse(item.catalogItemId(), item.productId(), item.itemName(), item.brandName(),
				item.categoryName(), item.description(), item.presentation(), money(item.unitPriceAmount(), item.unitPriceCurrency()),
				item.coldChainRequirement(), new CatalogMediaResponse(item.imageUrl(), item.imageFileName()), item.status(),
				item.availabilityStatus(), item.nearExpiry(), item.promotionLabel(), money(item.pricing().basePrice(), item.pricing().currency()),
				money(item.pricing().effectivePrice(), item.pricing().currency()), money(item.pricing().discountAmount(), item.pricing().currency()),
				item.pricing().currency(), item.pricing().appliedPromotions().stream()
						.map(promotion -> new com.nexa.api.catalogmanagement.presentation.rest.response.CatalogAppliedPromotionResponse(
								promotion.id(), promotion.name(), promotion.discountType(), promotion.discountAmount())).toList(), item.pricing().pricingAsOf(),
				item.productFamilyId(), item.productFamilyCode(), item.productFamilyName(), item.sellableSkuId(), item.skuCode(),
				item.unitOfMeasure(), item.packagingType(), item.netWeight(), item.grossWeight(), item.availabilityAsOf(),
				item.productVariantCode(), item.productVariantName());
	}

	public CatalogPageResponse toPage(CatalogPage<CatalogItemSummary> page) {
		return new CatalogPageResponse(page.items().stream().map(this::toSummary).toList(), page.page(), page.size(),
				page.totalItems(), page.totalPages(), new CatalogPageResponse.SortResponse(page.sortField().wireValue(), page.sortDirection().wireValue()));
	}

	private static MoneyResponse money(java.math.BigDecimal amount, String currency) {
		return new MoneyResponse(amount.toPlainString(), currency);
	}
}
