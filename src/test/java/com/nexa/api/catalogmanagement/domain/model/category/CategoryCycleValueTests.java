package com.nexa.api.catalogmanagement.domain.model.category;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryCycleValueTests {
	@Test
	void preservesRootAndParentReferencesWithoutTraversingCategoryGraph() {
		CategoryId rootId = new CategoryId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
		Category root = Category.create(rootId, null, " dairy ", " Dairy ", null);
		Category child = Category.create(
				new CategoryId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
				rootId,
				" fresh-cheese ",
				" Fresh cheese ",
				" Refrigerated products ");

		assertThat(root.parentId()).isNull();
		assertThat(child.parentId()).isEqualTo(rootId);
		assertThat(child.id()).isNotEqualTo(child.parentId());
		assertThat(child.slug()).isEqualTo("fresh-cheese");
	}

	@Test
	void rejectsSelfParentCycleAndMissingCategoryIdentifier() {
		CategoryId id = new CategoryId(UUID.fromString("44444444-4444-4444-4444-444444444444"));

		assertThatThrownBy(() -> Category.create(id, id, "dairy", "Dairy", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Category cannot be its own parent");
		assertThatThrownBy(() -> new CategoryId(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Category id is required");
	}

	@Test
	void normalizesValuesAllowsNullableDescriptionAndSupportsValueChanges() {
		Category category = category();

		assertThat(category.slug()).isEqualTo("dairy");
		assertThat(category.name()).isEqualTo("Dairy");
		assertThat(category.description()).isEqualTo("Refrigerated products");

		category.rename("  Cheese  ");
		category.changeSlug("  cheese  ");
		category.rewriteDescription(null);

		assertThat(category.name()).isEqualTo("Cheese");
		assertThat(category.slug()).isEqualTo("cheese");
		assertThat(category.description()).isNull();
	}

	@Test
	void validatesCategoryValuesAtCreationAndDuringChanges() {
		assertThatThrownBy(() -> Category.create(new CategoryId(UUID.randomUUID()), null, " ", "Dairy", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Category.create(new CategoryId(UUID.randomUUID()), null, "dairy", " ", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Category.create(new CategoryId(UUID.randomUUID()), null, "x".repeat(101), "Dairy", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Category.create(new CategoryId(UUID.randomUUID()), null, "dairy", "x".repeat(161), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Category.create(new CategoryId(UUID.randomUUID()), null, "dairy", "Dairy", "x".repeat(2001)))
				.isInstanceOf(IllegalArgumentException.class);

		Category category = category();
		assertThatThrownBy(() -> category.rename(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> category.changeSlug("x".repeat(101))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> category.rewriteDescription("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
	}

	private static Category category() {
		return Category.create(
				new CategoryId(UUID.fromString("55555555-5555-5555-5555-555555555555")),
				null,
				" dairy ",
				" Dairy ",
				" Refrigerated products ");
	}
}
