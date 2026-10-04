package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates the immutable persistence seed and its provisional visibility boundary. */
public final class CatalogPersistenceSeedValidator {
    public static final String EXPECTED_SHA256 = "792d02e9028752e971d124206a4d3c63ae46628157dcbb94a5f4b4e7b07fe27f";
    public static final int EXPECTED_COUNT = 102;
    public static final int EXPECTED_CURATED_COUNT = 50;
    public static final int EXPECTED_PROVISIONAL_COUNT = 52;
    private static final String IMAGE_PATH_PREFIX = "/catalog-items/";
    private static final String PROVISIONAL_PRICE_CODE = "PROVISIONAL_REFERENCE";
    private static final String PROVISIONAL_DESCRIPTION_PREFIX = "Precio referencial provisional";

    private CatalogPersistenceSeedValidator() {
    }

    public static void validate(List<CatalogPersistenceSeedItemRecord> items, byte[] rawContent) {
        if (items == null || items.size() != EXPECTED_COUNT) {
            throw new CatalogSeedIntegrityException("expected persistence item count " + EXPECTED_COUNT);
        }
        if (!EXPECTED_SHA256.equals(sha256(rawContent))) {
            throw new CatalogSeedIntegrityException("persistence resource checksum mismatch");
        }

        Set<String> catalogItemIds = new HashSet<>();
        Set<String> productIds = new HashSet<>();
        Set<String> imageFileNames = new HashSet<>();
        Set<String> imageUrls = new HashSet<>();
        int provisionalCount = 0;
        for (int index = 0; index < items.size(); index++) {
            CatalogPersistenceSeedItemRecord item = items.get(index);
            if (item == null) fail(index, "null item");
            String expectedSuffix = "%04d".formatted(index + 1);
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
            if (!item.catalogItemId().equals("CAT-" + expectedSuffix)) fail(index, "catalogItemId is not canonical");
            if (!item.productId().equals("PROD-" + expectedSuffix)) fail(index, "productId is not canonical");
            if (!catalogItemIds.add(item.catalogItemId())) fail(index, "duplicate catalogItemId");
            if (!productIds.add(item.productId())) fail(index, "duplicate productId");
            if (!imageFileNames.add(item.imageFileName())) fail(index, "duplicate imageFileName");
            if (!imageUrls.add(item.imageUrl())) fail(index, "duplicate imageUrl");
            if (item.unitPriceAmount() == null || item.unitPriceAmount().compareTo(BigDecimal.ZERO) < 0) {
                fail(index, "negative unitPriceAmount");
            }
            if (!"PEN".equals(item.unitPriceCurrency())) fail(index, "unitPriceCurrency must be PEN");
            if (!"Refrigerated".equals(item.coldChainRequirement())) {
                fail(index, "coldChainRequirement must be Refrigerated");
            }
            if (!item.imageFileName().matches("[A-Za-z0-9._-]+") || item.imageFileName().contains("..")) {
                fail(index, "unsafe imageFileName");
            }
            if (!item.imageUrl().equals(IMAGE_PATH_PREFIX + item.imageFileName())) {
                fail(index, "imageUrl does not match imageFileName");
            }

            if (item.provisionalReference()) {
                provisionalCount++;
                if (item.buyerVisible()) fail(index, "provisional item must not be buyer visible");
                if (!PROVISIONAL_PRICE_CODE.equals(item.sourcePriceCode())) {
                    fail(index, "provisional item must use PROVISIONAL_REFERENCE price code");
                }
                if (!item.sourcePriceDescription().startsWith(PROVISIONAL_DESCRIPTION_PREFIX)) {
                    fail(index, "provisional item must carry provisional price description");
                }
            } else if (!item.buyerVisible()) {
                fail(index, "curated item must remain buyer visible");
            }
        }
        if (provisionalCount != EXPECTED_PROVISIONAL_COUNT) {
            throw new CatalogSeedIntegrityException("expected provisional item count " + EXPECTED_PROVISIONAL_COUNT);
        }
        if (EXPECTED_COUNT - provisionalCount != EXPECTED_CURATED_COUNT) {
            throw new CatalogSeedIntegrityException("expected curated item count " + EXPECTED_CURATED_COUNT);
        }
    }

    private static void require(int index, String value, String field) {
        if (value == null || value.isBlank()) fail(index, "missing " + field);
    }

    private static void fail(int index, String reason) {
        throw new CatalogSeedIntegrityException("persistence item index " + index + ": " + reason);
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
