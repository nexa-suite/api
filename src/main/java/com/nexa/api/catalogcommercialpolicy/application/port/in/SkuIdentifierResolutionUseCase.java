package com.nexa.api.catalogcommercialpolicy.application.port.in;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

import java.util.UUID;

/** Public BC-03 use case for resolving an exact sellable SKU identifier. */
public interface SkuIdentifierResolutionUseCase {
    Resolution resolve(CatalogScope scope, String identifier);

    record Resolution(String outcome, String identifierType, String normalizedIdentifier,
                      int candidateCount, UUID skuId, String skuCode, String gtin,
                      String presentation, String unitOfMeasure, String status) { }
}
