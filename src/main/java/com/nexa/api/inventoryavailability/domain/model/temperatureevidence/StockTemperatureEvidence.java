package com.nexa.api.inventoryavailability.domain.model.temperatureevidence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable manual temperature evidence for a stock lot or warehouse. */
public record StockTemperatureEvidence(
        UUID id,
        TemperatureEvidenceSubject subjectType,
        UUID lotId,
        UUID warehouseId,
        BigDecimal value,
        TemperatureUnit unit,
        Instant observedAt,
        Instant recordedAt,
        UUID actorMembershipId,
        TemperatureEvidenceStatus status) {

    public StockTemperatureEvidence {
        Objects.requireNonNull(id, "Evidence id is required");
        Objects.requireNonNull(subjectType, "Evidence subject is required");
        Objects.requireNonNull(warehouseId, "Warehouse is required");
        Objects.requireNonNull(value, "Temperature value is required");
        Objects.requireNonNull(unit, "Temperature unit is required");
        Objects.requireNonNull(observedAt, "Observed time is required");
        Objects.requireNonNull(recordedAt, "Recorded time is required");
        Objects.requireNonNull(actorMembershipId, "Actor membership is required");
        Objects.requireNonNull(status, "Evidence status is required");
        validateMeasurement(value, unit, observedAt);
        if (subjectType == TemperatureEvidenceSubject.LOT && lotId == null
                || subjectType == TemperatureEvidenceSubject.WAREHOUSE && lotId != null) {
            throw new IllegalArgumentException("Evidence subject is incomplete");
        }
    }

    public static void validateMeasurement(BigDecimal value, TemperatureUnit unit, Instant observedAt) {
        Objects.requireNonNull(value, "Temperature value is required");
        Objects.requireNonNull(unit, "Temperature unit is required");
        Objects.requireNonNull(observedAt, "Observed time is required");
    }
}
