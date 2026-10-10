package com.nexa.api.fulfillmentdelivery.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Attributable comparison of observed dock goods with one current physical allocation. */
public final class OutgoingGoodsCheckModels {
    private OutgoingGoodsCheckModels() { }

    public record Observation(UUID physicalAllocationLineId, UUID observedLotId,
                              BigDecimal observedQuantity) { }

    public record Request(UUID physicalAllocationId, long physicalAllocationVersion,
                          List<Observation> observations) {
        public Request {
            observations = List.copyOf(observations == null ? List.of() : observations);
        }
    }

    public record ResolutionRequest(UUID physicalAllocationId, long physicalAllocationVersion,
                                    UUID discrepancyCheckId, UUID matchingCheckId, String reason) { }

    public record Line(UUID physicalAllocationLineId, UUID skuId, UUID expectedLotId,
                       UUID observedLotId, BigDecimal expectedQuantity,
                       BigDecimal observedQuantity, String unit, boolean matches) { }

    public record DiscrepancyDetails(UUID id, long fulfillmentVersion,
                                     UUID physicalAllocationId, long physicalAllocationVersion,
                                     UUID checkedByMembershipId, Instant checkedAt, List<Line> lines) {
        public DiscrepancyDetails {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    public record Check(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                        UUID physicalAllocationId, long physicalAllocationVersion,
                        boolean matches, boolean current, boolean openDiscrepancy,
                        UUID checkedByMembershipId, Instant checkedAt,
                        List<Line> lines, boolean replayed, DiscrepancyDetails discrepancy) {
        public Check {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    /** Minimal current check facts consumed by Dispatch; Warehouse retains the full evidence projection. */
    public record DispatchSummary(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                                  UUID physicalAllocationId, long physicalAllocationVersion,
                                  boolean matches, boolean current, boolean openDiscrepancy) { }

    public record DiscrepancyResolution(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                                        UUID physicalAllocationId, long physicalAllocationVersion,
                                        UUID discrepancyCheckId, UUID matchingCheckId,
                                        UUID actorMembershipId, String reason, Instant resolvedAt,
                                        boolean current, boolean replayed) { }
}
