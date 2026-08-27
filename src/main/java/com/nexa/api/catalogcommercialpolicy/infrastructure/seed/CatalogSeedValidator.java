package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CatalogSeedValidator {
	public static final String EXPECTED_SHA256 = "c9fdb629c3996f78918081a6e7f6598ce44c8ede05a8208c7f77785367ca096b";
	private static final int EXPECTED_COUNT = 50;
	private static final String IMAGE_PATH_PREFIX = "/catalog-items/";

	private CatalogSeedValidator() {
	}

	public static void validate(List<CatalogSeedItemRecord> items, byte[] rawContent) {
		if (items == null || items.size() != EXPECTED_COUNT) {
			throw new CatalogSeedIntegrityException("expected item count " + EXPECTED_COUNT);
		}
		if (!EXPECTED_SHA256.equals(sha256(rawContent))) {
			throw new CatalogSeedIntegrityException("resource checksum mismatch");
		}
		Set<String> catalogItemIds = new HashSet<>();
		Set<String> productIds = new HashSet<>();
		Set<String> imageFileNames = new HashSet<>();
		Set<String> imageUrls = new HashSet<>();
		for (int index = 0; index < items.size(); index++) {
			CatalogSeedItemRecord item = items.get(index);
			if (item == null) fail(index, "null item");
			require(index, item.catalogItemId(), "catalogItemId");
			require(index, item.productId(), "productId");
			require(index, item.itemName(), "itemName");
			require(index, item.brandName(), "brandName");
			require(index, item.categoryName(), "categoryName");
			require(index, item.description(), "description");
			require(index, item.unitPriceCurrency(), "unitPriceCurrency");
			require(index, item.coldChainRequirement(), "coldChainRequirement");
			require(index, item.imageUrl(), "imageUrl");
			require(index, item.imageFileName(), "imageFileName");
			require(index, item.presentation(), "presentation");
			require(index, item.sourcePriceCode(), "sourcePriceCode");
			require(index, item.sourcePriceDescription(), "sourcePriceDescription");
			if (!catalogItemIds.add(item.catalogItemId())) fail(index, "duplicate catalogItemId");
			if (!productIds.add(item.productId())) fail(index, "duplicate productId");
			if (!imageFileNames.add(item.imageFileName())) fail(index, "duplicate imageFileName");
			if (!imageUrls.add(item.imageUrl())) fail(index, "duplicate imageUrl");
			if (item.unitPriceAmount() == null || item.unitPriceAmount().compareTo(BigDecimal.ZERO) < 0) fail(index, "negative unitPriceAmount");
			if (item.availableStock() < 0) fail(index, "negative availableStock");
			if (!"PEN".equals(item.unitPriceCurrency())) fail(index, "unitPriceCurrency must be PEN");
			if (!"Refrigerated".equals(item.coldChainRequirement())) fail(index, "coldChainRequirement must be Refrigerated");
			if (!item.imageFileName().matches("[A-Za-z0-9._-]+") || item.imageFileName().contains("..")) fail(index, "unsafe imageFileName");
			if (!item.imageUrl().equals(IMAGE_PATH_PREFIX + item.imageFileName())) fail(index, "imageUrl does not match imageFileName");
		}
	}

	private static void require(int index, String value, String field) {
		if (value == null || value.isBlank()) fail(index, "missing " + field);
	}

	private static void fail(int index, String reason) {
		throw new CatalogSeedIntegrityException("item index " + index + ": " + reason);
	}

	private static String sha256(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder result = new StringBuilder(digest.length * 2);
			for (byte value : digest) result.append(String.format("%02x", value));
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
