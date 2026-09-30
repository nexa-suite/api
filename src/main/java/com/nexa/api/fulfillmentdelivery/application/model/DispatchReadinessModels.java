package com.nexa.api.fulfillmentdelivery.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** Current, read-only preparation facts for the dispatch boundary. */
public final class DispatchReadinessModels {
    private DispatchReadinessModels() { }

    @Schema(name = "DispatchReadinessPage")
    public record Page(List<Readiness> items, int page, int size, long totalItems, Instant asOf) {
        public Page {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    @Schema(name = "DispatchReadiness")
    public record Readiness(String subjectKind,
                            UUID fulfillmentId,
                            long fulfillmentVersion,
                            String fulfillmentStatus,
                            UUID physicalAllocationId,
                            String physicalAllocationStatus,
                            long physicalAllocationVersion,
                            UUID deliveryId,
                            String deliveryStatus,
                            Long deliveryVersion,
                            boolean allocationComplete,
                            boolean pickingComplete,
                            boolean pickingEvidenceComplete,
                            boolean ready,
                            List<String> reasons,
                            List<LineReadiness> lines,
                            Instant asOf) {
        public Readiness {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    @Schema(name = "DispatchReadinessLine")
    public record LineReadiness(UUID fulfillmentLineId,
                                UUID skuId,
                                String catalogItemId,
                                BigDecimal allocatedQuantity,
                                BigDecimal physicallyAllocatedQuantity,
                                BigDecimal pickedQuantity,
                                BigDecimal evidencedPickedQuantity,
                                boolean allocationComplete,
                                boolean pickingComplete,
                                boolean evidenceComplete) { }
}
