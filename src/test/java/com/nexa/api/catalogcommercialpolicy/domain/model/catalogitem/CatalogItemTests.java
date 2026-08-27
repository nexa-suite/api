package com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogItemTests {
	@Test
	void createsActiveItemWithEncapsulatedState() {
		CatalogItem item = item();

		assertThat(item.status()).isEqualTo(CatalogItemStatus.ACTIVE);
		assertThat(item.catalogItemId().value()).isEqualTo("CAT-0001");
		assertThat(item.itemName().value()).isEqualTo("Original name");
	}

	@Test
	void changesEveryIntentField() {
		CatalogItem item = item();
		item.rename(new ItemName("Renamed"));
		item.changeBrand(new BrandName("New Brand"));
		item.reclassify(new CategoryName("New Category"));
		item.rewriteDescription(new CatalogDescription("New description"));
		item.changePresentation(new ProductPresentation("BOX 2"));
		item.changeUnitPrice(new Money(new BigDecimal("19.90"), Currency.getInstance("PEN")));
		item.changeColdChainRequirement(ColdChainRequirement.FROZEN);
		item.changeMedia(new CatalogMedia("/catalog-items/new.webp", "new.webp"));

		assertThat(item.itemName().value()).isEqualTo("Renamed");
		assertThat(item.brandName().value()).isEqualTo("New Brand");
		assertThat(item.categoryName().value()).isEqualTo("New Category");
		assertThat(item.description().value()).isEqualTo("New description");
		assertThat(item.presentation().value()).isEqualTo("BOX 2");
		assertThat(item.unitPrice().amount()).isEqualByComparingTo("19.90");
		assertThat(item.coldChainRequirement()).isEqualTo(ColdChainRequirement.FROZEN);
		assertThat(item.media().imageFileName()).isEqualTo("new.webp");
	}

	@Test
	void activationMethodsAreIdempotent() {
		CatalogItem item = item();
		item.deactivate();
		item.deactivate();
		assertThat(item.status()).isEqualTo(CatalogItemStatus.INACTIVE);
		item.activate();
		item.activate();
		assertThat(item.status()).isEqualTo(CatalogItemStatus.ACTIVE);
	}

	@Test
	void rejectsNullCreationAndChanges() {
		assertThatThrownBy(() -> CatalogItem.create(null, productId(), itemName(), brand(), category(), description(), presentation(), price(), requirement(), media()))
				.isInstanceOf(CatalogInvariantViolation.class);
		CatalogItem item = item();
		assertThatThrownBy(() -> item.rename(null)).isInstanceOf(CatalogInvariantViolation.class);
		assertThatThrownBy(() -> item.changeUnitPrice(null)).isInstanceOf(CatalogInvariantViolation.class);
	}

	@Test
	void aggregateHasNoStockState() {
		assertThat(java.util.Arrays.stream(CatalogItem.class.getDeclaredFields()).map(field -> field.getName()))
				.doesNotContain("availableStock", "reserveStock", "synchronizeAvailableStock", "stockQuantity", "inventoryReservation");
	}

	private static CatalogItem item() {
		return CatalogItem.create(new CatalogItemId("cat-0001"), productId(), itemName(), brand(), category(), description(), presentation(), price(), requirement(), media());
	}

	private static ProductId productId() { return new ProductId("prod-0001"); }
	private static ItemName itemName() { return new ItemName("Original name"); }
	private static BrandName brand() { return new BrandName("Brand"); }
	private static CategoryName category() { return new CategoryName("Category"); }
	private static CatalogDescription description() { return new CatalogDescription("Description"); }
	private static ProductPresentation presentation() { return new ProductPresentation("BOX 1"); }
	private static Money price() { return new Money(new BigDecimal("10.50"), Currency.getInstance("PEN")); }
	private static ColdChainRequirement requirement() { return ColdChainRequirement.REFRIGERATED; }
	private static CatalogMedia media() { return new CatalogMedia("/catalog-items/item.png", "item.png"); }
}
