package com.nexa.api.inventoryavailability.application.publicapi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** BC-05 read boundary for the batch identifiers already owned by inventory lots. */
public interface LotIdentifierResolutionQuery {
    List<Candidate> resolve(UUID tenantId, UUID workspaceId, String batchNumber);

    record Candidate(UUID lotId, UUID skuId, String catalogItemId, UUID warehouseId, UUID zoneId,
                     String batchNumber, LocalDate expirationDate, Instant receivedAt, String status,
                     String unit) { }
}
