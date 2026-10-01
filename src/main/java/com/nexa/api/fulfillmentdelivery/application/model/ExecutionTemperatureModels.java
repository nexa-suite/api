package com.nexa.api.fulfillmentdelivery.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ExecutionTemperatureModels {
    private ExecutionTemperatureModels() { }
    public enum Disposition { RELEASE, CONTINUE_HOLD, REJECT, WASTE }
    public record ReadingCommand(UUID fulfillmentLineId, UUID skuId, BigDecimal affectedQuantity,
            BigDecimal value, String unit, Instant occurredAt, UUID sourceIncidentId, UUID evidenceObjectId) { }
    public record Line(UUID fulfillmentLineId, UUID skuId, String unit, BigDecimal remainingQuantity,
            boolean coldChainRequired, BigDecimal minimumCelsius, BigDecimal maximumCelsius) { }
    public record Hold(UUID id, UUID readingId, UUID exceptionId, UUID fulfillmentLineId, UUID skuId,
            BigDecimal affectedQuantity, String quantityUnit, String status, UUID reportedByMembershipId,
            Instant reportedAt, Disposition disposition, UUID authorizedByMembershipId, Instant disposedAt,
            String reason) { }
    public record Reading(UUID id, UUID deliveryId, UUID attemptId, UUID fulfillmentLineId, UUID skuId,
            BigDecimal affectedQuantity, String quantityUnit, BigDecimal valueCelsius, String temperatureUnit,
            BigDecimal minimumCelsius, BigDecimal maximumCelsius, String status,
            UUID actorMembershipId, Instant occurredAt, Instant recordedAt, UUID evidenceObjectId,
            UUID sourceIncidentId, Hold hold, long deliveryVersion, boolean replayed) { }
    public record DispositionResult(Hold hold, long deliveryVersion, boolean replayed) { }
    public record Snapshot(UUID deliveryId, long deliveryVersion, String deliveryStatus,
            UUID attemptId, UUID originWarehouseId, List<Line> lines, List<Hold> holds) {
        public Snapshot { lines=List.copyOf(lines); holds=List.copyOf(holds); }
    }
    public record Scope(UUID tenantId, UUID workspaceId, UUID actorMembershipId, UUID deliveryId) { }
    public record Delivery(UUID id, UUID fulfillmentId, UUID warehouseId, long version, String status, UUID attemptId) { }
    public record RawLine(UUID id, UUID skuId, String unit, BigDecimal remainingQuantity) { }
}
