package com.nexa.api.inventoryavailability.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** BC-05 read-only application operation for resolving a batch identifier. */
@org.springframework.modulith.NamedInterface("warehouse-operations")
public interface LotIdentifierResolutionUseCase {
    Resolution resolve(CurrentAccessContext context, String batchNumber);

    @org.springframework.modulith.NamedInterface("warehouse-operations")
    record Resolution(String outcome, String identifierType, String normalizedIdentifier,
                      int candidateCount, UUID lotId, UUID skuId, String catalogItemId,
                      UUID warehouseId, UUID zoneId, LocalDate expirationDate,
                      Instant receivedAt, String status, String unit) { }
}
