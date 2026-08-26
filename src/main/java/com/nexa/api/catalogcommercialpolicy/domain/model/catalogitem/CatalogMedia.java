package com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem;

public record CatalogMedia(String imageUrl, String imageFileName) {
	public CatalogMedia {
		if (imageUrl == null || imageUrl.isBlank()) throw new CatalogInvariantViolation("Catalog image URL is required");
		if (imageFileName == null || imageFileName.isBlank()) throw new CatalogInvariantViolation("Catalog image filename is required");
		imageUrl = imageUrl.trim();
		imageFileName = imageFileName.trim();
		if (imageUrl.length() > 240) throw new CatalogInvariantViolation("Catalog image URL exceeds 240 characters");
		if (!imageUrl.startsWith("/catalog-items/") || imageUrl.contains("?") || imageUrl.contains("#") || imageUrl.contains("..") || imageUrl.contains("\\")) {
			throw new CatalogInvariantViolation("Catalog image URL must be a safe relative catalog path");
		}
		if (!imageFileName.matches("[A-Za-z0-9._-]+") || imageFileName.contains("..") || !imageFileName.matches("(?i).+\\.(png|jpe?g|webp)")) {
			throw new CatalogInvariantViolation("Catalog image filename is unsafe");
		}
		if (!imageUrl.endsWith("/" + imageFileName)) throw new CatalogInvariantViolation("Catalog image URL must end with filename");
	}
}
