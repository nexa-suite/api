package com.nexa.api.inventoryavailability.application.service;

import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application contract for read-only batch/lot identifier resolution. */
@Service
@Profile("!test")
public class LotIdentifierResolutionService {
    private final LotIdentifierResolutionQuery query;

    public LotIdentifierResolutionService(LotIdentifierResolutionQuery query) {
        this.query = Objects.requireNonNull(query, "Lot identifier query is required");
    }

    @Transactional(readOnly = true)
    public Resolution resolve(CurrentAccessContext context, String batchNumber) {
        context.requirePermission(PermissionKey.INVENTORY_READ);
        if (batchNumber == null || batchNumber.isBlank() || batchNumber.trim().length() > 160) {
            throw new IllegalArgumentException("Batch number is required");
        }
        String normalized = batchNumber.trim();
        List<LotIdentifierResolutionQuery.Candidate> candidates = query.resolve(
                context.tenantId().value(), context.workspaceId().value(), normalized);
        String outcome = candidates.isEmpty() ? "NOT_FOUND" : candidates.size() == 1 ? "RESOLVED" : "AMBIGUOUS";
        LotIdentifierResolutionQuery.Candidate candidate = candidates.size() == 1 ? candidates.get(0) : null;
        return new Resolution(outcome, "BATCH_NUMBER", normalized, candidates.size(),
                candidate == null ? null : candidate.lotId(), candidate == null ? null : candidate.skuId(),
                candidate == null ? null : candidate.catalogItemId(), candidate == null ? null : candidate.warehouseId(),
                candidate == null ? null : candidate.zoneId(), candidate == null ? null : candidate.expirationDate(),
                candidate == null ? null : candidate.receivedAt(), candidate == null ? null : candidate.status(),
                candidate == null ? null : candidate.unit());
    }

    public record Resolution(String outcome, String identifierType, String normalizedIdentifier,
                             int candidateCount, UUID lotId, UUID skuId, String catalogItemId,
                             UUID warehouseId, UUID zoneId, java.time.LocalDate expirationDate,
                             java.time.Instant receivedAt, String status, String unit) { }
}
