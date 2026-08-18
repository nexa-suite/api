package com.nexa.api.catalogmanagement.domain.model.product;

import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductLifecycleTests {
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	void startsAsDraftAndNormalizesCreationValues() {
		Product product = product();

		assertThat(product.id()).isEqualTo(PRODUCT_ID);
		assertThat(product.catalogItemId()).isEqualTo("CAT-0001");
		assertThat(product.productCode()).isEqualTo("SKU-0001");
		assertThat(product.slug()).isEqualTo("fresh-cheese");
		assertThat(product.name()).isEqualTo("Fresh cheese");
		assertThat(product.description()).isEqualTo("Refrigerated product");
		assertThat(product.status()).isEqualTo(CatalogItemStatus.DRAFT);
	}

	@Test
	void supportsActivationDeactivationAndDiscontinuationTransitions() {
		Product active = product();
		active.activate();
		assertThat(active.status()).isEqualTo(CatalogItemStatus.ACTIVE);

		active.deactivate();
		assertThat(active.status()).isEqualTo(CatalogItemStatus.INACTIVE);
		active.activate();
		assertThat(active.status()).isEqualTo(CatalogItemStatus.ACTIVE);
		active.discontinue();
		assertThat(active.status()).isEqualTo(CatalogItemStatus.DISCONTINUED);

		Product draft = product();
		draft.deactivate();
		assertThat(draft.status()).isEqualTo(CatalogItemStatus.INACTIVE);
		Product discontinued = product();
		discontinued.discontinue();
		assertThat(discontinued.status()).isEqualTo(CatalogItemStatus.DISCONTINUED);
	}

	@Test
	void archivesNonActiveProductsAndGuardsActiveAndArchivedTransitions() {
		Product inactive = product();
		inactive.deactivate();
		inactive.archive();
		assertThat(inactive.status()).isEqualTo(CatalogItemStatus.ARCHIVED);

		Product active = product();
		active.activate();
		assertThatThrownBy(active::archive)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Active product cannot be archived");

		assertThatThrownBy(inactive::activate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Archived product cannot be activated");
		assertThatThrownBy(inactive::deactivate)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Archived product cannot be deactivated");
	}

	@Test
	void validatesRequiredAndBoundedCreationValues() {
		assertThatThrownBy(() -> Product.create(null, "CAT-0001", "SKU-0001", "fresh-cheese", "Fresh cheese", "Description"))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Product.create(PRODUCT_ID, " ", "SKU-0001", "fresh-cheese", "Fresh cheese", "Description"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Product.create(PRODUCT_ID, "x".repeat(65), "SKU-0001", "fresh-cheese", "Fresh cheese", "Description"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Product.create(PRODUCT_ID, "CAT-0001", "x".repeat(65), "fresh-cheese", "Fresh cheese", "Description"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Product.create(PRODUCT_ID, "CAT-0001", "SKU-0001", "x".repeat(141), "Fresh cheese", "Description"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Product.create(PRODUCT_ID, "CAT-0001", "SKU-0001", "fresh-cheese", "x".repeat(201), "Description"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Product.create(PRODUCT_ID, "CAT-0001", "SKU-0001", "fresh-cheese", "Fresh cheese", "x".repeat(4001)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void updatesNameAndDescriptionAndValidatesChanges() {
		Product product = product();

		product.rename("  Mature cheese  ");
		product.rewriteDescription("  Updated description  ");

		assertThat(product.name()).isEqualTo("Mature cheese");
		assertThat(product.description()).isEqualTo("Updated description");
		assertThatThrownBy(() -> product.rename(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> product.rewriteDescription(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> product.rename("x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> product.rewriteDescription("x".repeat(4001))).isInstanceOf(IllegalArgumentException.class);
	}

	private static Product product() {
		return Product.create(PRODUCT_ID, " CAT-0001 ", " SKU-0001 ", " fresh-cheese ", " Fresh cheese ", " Refrigerated product ");
	}
}
