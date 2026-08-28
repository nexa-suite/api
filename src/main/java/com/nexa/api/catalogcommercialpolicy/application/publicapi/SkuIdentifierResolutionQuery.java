package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import java.util.List;
import java.util.UUID;

/** BC-03 read boundary for resolving the identifiers already owned by Catalog. */
public interface SkuIdentifierResolutionQuery {
    List<Candidate> resolve(UUID tenantId, UUID workspaceId, String identifier);

    record Candidate(UUID skuId, String skuCode, String gtin, String presentation,
                     String unitOfMeasure, String status, boolean visible) { }
}
