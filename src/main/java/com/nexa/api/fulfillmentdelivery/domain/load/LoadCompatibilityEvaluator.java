package com.nexa.api.fulfillmentdelivery.domain.load;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Evaluates only authoritative, structured facts; unknown input fails closed. */
public final class LoadCompatibilityEvaluator {
    private static final Set<String> ROUTEABLE_DISPATCH_STATUSES = Set.of(
            "READY_FOR_OPERATIONS", "PREPARING", "ASSIGNED", "SCHEDULED", "READY_FOR_ROUTE", "REPROGRAMMED");

    private LoadCompatibilityEvaluator() { }

    public static Evaluation evaluate(List<DeliveryFacts> deliveries) {
        if (deliveries == null || deliveries.size() < 2) {
            return Evaluation.reject("LOAD_REQUIRES_MULTIPLE_DELIVERIES");
        }
        if (deliveries.stream().anyMatch(Objects::isNull)) return Evaluation.reject("LOAD_FACTS_UNAVAILABLE");
        Set<UUID> ids = new HashSet<>();
        for (DeliveryFacts delivery : deliveries) {
            if (delivery.fulfillmentId() == null || !ids.add(delivery.fulfillmentId())) {
                return Evaluation.reject("LOAD_DELIVERIES_INVALID");
            }
            if (!"READY_FOR_DISPATCH".equals(delivery.fulfillmentStatus())
                    || delivery.deliveryStatus() != null && !"PLANNED".equals(delivery.deliveryStatus())) {
                return Evaluation.reject("DELIVERY_NOT_READY_FOR_LOAD");
            }
            if (delivery.fulfillmentHeld() || delivery.unresolvedBlockingOrCriticalIncident()) {
                return Evaluation.reject("DELIVERY_OPERATIONAL_HOLD");
            }
            if (delivery.hasExistingDriverAssignment() || delivery.hasExistingLoad()) {
                return Evaluation.reject("DELIVERY_ALREADY_ASSIGNED");
            }
            if (delivery.structuredExclusiveTransportRestriction()) {
                return Evaluation.reject("DELIVERY_EXCLUSIVE_TRANSPORT_REQUIRED");
            }
            if (delivery.originWarehouseId() == null || delivery.windowStart() == null || delivery.windowEnd() == null
                    || delivery.temperatureUnit() == null || delivery.temperatureUnit().isBlank()) {
                return Evaluation.reject("LOAD_COMPATIBILITY_FACTS_UNAVAILABLE");
            }
            if (delivery.dispatchOrderStatus() != null
                    && !ROUTEABLE_DISPATCH_STATUSES.contains(delivery.dispatchOrderStatus())
                    || "OUT_OF_RANGE".equals(delivery.temperatureStatus())) {
                return Evaluation.reject("DELIVERY_NOT_READY_FOR_LOAD");
            }
            boolean boundedTemperature = delivery.temperatureMin() != null && delivery.temperatureMax() != null;
            boolean explicitNoRequirement = delivery.temperatureMin() == null && delivery.temperatureMax() == null
                    && "NONE".equalsIgnoreCase(delivery.temperatureUnit());
            if (!boundedTemperature && !explicitNoRequirement) {
                return Evaluation.reject("LOAD_COMPATIBILITY_FACTS_INVALID");
            }
            if (boundedTemperature && delivery.temperatureMin().compareTo(delivery.temperatureMax()) > 0) {
                return Evaluation.reject("LOAD_COMPATIBILITY_FACTS_INVALID");
            }
        }

        DeliveryFacts first = deliveries.getFirst();
        Instant commonStart = first.windowStart();
        Instant commonEnd = first.windowEnd();
        for (DeliveryFacts delivery : deliveries.subList(1, deliveries.size())) {
            if (!first.originWarehouseId().equals(delivery.originWarehouseId())) {
                return Evaluation.reject("LOAD_ORIGIN_WAREHOUSE_MISMATCH");
            }
            if (!sameTemperatureRequirement(first, delivery)) {
                return Evaluation.reject("LOAD_TEMPERATURE_REQUIREMENTS_MISMATCH");
            }
            if (delivery.windowStart().isAfter(commonStart)) commonStart = delivery.windowStart();
            if (delivery.windowEnd().isBefore(commonEnd)) commonEnd = delivery.windowEnd();
        }
        if (commonStart.isAfter(commonEnd)) return Evaluation.reject("LOAD_DELIVERY_WINDOWS_INCOMPATIBLE");
        return new Evaluation(true, null, first.originWarehouseId(), commonStart, commonEnd,
                first.temperatureMin(), first.temperatureMax(), first.temperatureUnit());
    }

    private static boolean sameTemperatureRequirement(DeliveryFacts left, DeliveryFacts right) {
        boolean leftNone = left.temperatureMin() == null && left.temperatureMax() == null;
        boolean rightNone = right.temperatureMin() == null && right.temperatureMax() == null;
        if (leftNone || rightNone) {
            return leftNone && rightNone && left.temperatureUnit().equalsIgnoreCase(right.temperatureUnit());
        }
        return left.temperatureMin().compareTo(right.temperatureMin()) == 0
                && left.temperatureMax().compareTo(right.temperatureMax()) == 0
                && left.temperatureUnit().equalsIgnoreCase(right.temperatureUnit());
    }

    public record DeliveryFacts(UUID deliveryId,
                                long deliveryVersion,
                                UUID fulfillmentId,
                                long fulfillmentVersion,
                                String fulfillmentStatus,
                                String deliveryStatus,
                                UUID originWarehouseId,
                                Instant windowStart,
                                Instant windowEnd,
                                BigDecimal temperatureMin,
                                BigDecimal temperatureMax,
                                String temperatureUnit,
                                String temperatureStatus,
                                String dispatchOrderStatus,
                                boolean fulfillmentHeld,
                                boolean unresolvedBlockingOrCriticalIncident,
                                boolean structuredExclusiveTransportRestriction,
                                boolean hasExistingDriverAssignment,
                                boolean hasExistingLoad) { }

    public record Evaluation(boolean compatible,
                             String reason,
                             UUID originWarehouseId,
                             Instant commonWindowStart,
                             Instant commonWindowEnd,
                             BigDecimal temperatureMin,
                             BigDecimal temperatureMax,
                             String temperatureUnit) {
        private static Evaluation reject(String reason) {
            return new Evaluation(false, reason, null, null, null, null, null, null);
        }
    }
}
