package com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem;

public final class CatalogItem {
	private final CatalogItemId catalogItemId;
	private final ProductId productId;
	private ItemName itemName;
	private BrandName brandName;
	private CategoryName categoryName;
	private CatalogDescription description;
	private ProductPresentation presentation;
	private Money unitPrice;
	private ColdChainRequirement coldChainRequirement;
	private CatalogMedia media;
	private CatalogItemStatus status;

	private CatalogItem(CatalogItemId catalogItemId, ProductId productId, ItemName itemName, BrandName brandName,
			CategoryName categoryName, CatalogDescription description, ProductPresentation presentation, Money unitPrice,
			ColdChainRequirement coldChainRequirement, CatalogMedia media) {
		this.catalogItemId = required(catalogItemId, "Catalog item id");
		this.productId = required(productId, "Product id");
		this.itemName = required(itemName, "Item name");
		this.brandName = required(brandName, "Brand name");
		this.categoryName = required(categoryName, "Category name");
		this.description = required(description, "Catalog description");
		this.presentation = required(presentation, "Product presentation");
		this.unitPrice = required(unitPrice, "Unit price");
		this.coldChainRequirement = required(coldChainRequirement, "Cold-chain requirement");
		this.media = required(media, "Catalog media");
		this.status = CatalogItemStatus.ACTIVE;
	}

	public static CatalogItem create(CatalogItemId catalogItemId, ProductId productId, ItemName itemName,
			BrandName brandName, CategoryName categoryName, CatalogDescription description, ProductPresentation presentation,
			Money unitPrice, ColdChainRequirement coldChainRequirement, CatalogMedia media) {
		return new CatalogItem(catalogItemId, productId, itemName, brandName, categoryName, description, presentation,
				unitPrice, coldChainRequirement, media);
	}

	public CatalogItemId catalogItemId() { return catalogItemId; }
	public ProductId productId() { return productId; }
	public ItemName itemName() { return itemName; }
	public BrandName brandName() { return brandName; }
	public CategoryName categoryName() { return categoryName; }
	public CatalogDescription description() { return description; }
	public ProductPresentation presentation() { return presentation; }
	public Money unitPrice() { return unitPrice; }
	public ColdChainRequirement coldChainRequirement() { return coldChainRequirement; }
	public CatalogMedia media() { return media; }
	public CatalogItemStatus status() { return status; }

	public void rename(ItemName newItemName) { itemName = required(newItemName, "Item name"); }
	public void changeBrand(BrandName newBrandName) { brandName = required(newBrandName, "Brand name"); }
	public void reclassify(CategoryName newCategoryName) { categoryName = required(newCategoryName, "Category name"); }
	public void rewriteDescription(CatalogDescription newDescription) { description = required(newDescription, "Catalog description"); }
	public void changePresentation(ProductPresentation newPresentation) { presentation = required(newPresentation, "Product presentation"); }
	public void changeUnitPrice(Money newUnitPrice) { unitPrice = required(newUnitPrice, "Unit price"); }
	public void changeColdChainRequirement(ColdChainRequirement newRequirement) { coldChainRequirement = required(newRequirement, "Cold-chain requirement"); }
	public void changeMedia(CatalogMedia newMedia) { media = required(newMedia, "Catalog media"); }
	public void activate() { status = CatalogItemStatus.ACTIVE; }
	public void deactivate() { status = CatalogItemStatus.INACTIVE; }
	public void discontinue() { status = CatalogItemStatus.DISCONTINUED; }
	public void archive() {
		if (status == CatalogItemStatus.ACTIVE) throw new CatalogInvariantViolation("Active catalog item cannot be archived");
		status = CatalogItemStatus.ARCHIVED;
	}

	private static <T> T required(T value, String label) {
		if (value == null) throw new CatalogInvariantViolation(label + " is required");
		return value;
	}
}
