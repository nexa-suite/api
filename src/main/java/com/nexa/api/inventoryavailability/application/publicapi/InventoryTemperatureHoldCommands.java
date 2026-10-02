package com.nexa.api.inventoryavailability.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-05-owned preventive inventory hold for a manual fulfillment temperature excursion. */
public interface InventoryTemperatureHoldCommands {
    /** Lock the target lot and verify the caller's warehouse grant and expected version. */
    void verifyTemperatureEvidenceTarget(CurrentAccessContext context, UUID lotId, UUID warehouseId,
                                         long expectedLotVersion);

    PreventiveHoldResult recordPreventiveTemperatureExcursion(CurrentAccessContext context,
                                                               PreventiveHoldRequest request);

    PreventiveHoldResult recordStockTemperatureEvidenceHold(CurrentAccessContext context,
                                                             StockEvidenceHoldRequest request);

    TemperatureEvaluationSnapshot temperatureEvaluation(CurrentAccessContext context, UUID evaluationId);

    record PreventiveHoldRequest(UUID lotId, UUID warehouseId, long expectedLotVersion,
                                 UUID fulfillmentId, long fulfillmentVersion,
                                 BigDecimal valueCelsius, BigDecimal minimumCelsius,
                                 BigDecimal maximumCelsius, BigDecimal affectedQuantity,
                                 UUID temperatureEvidenceId, UUID evidenceObjectId, Instant occurredAt) {
        public PreventiveHoldRequest {
            if (lotId == null || warehouseId == null || expectedLotVersion < 0
                    || fulfillmentId == null || fulfillmentVersion < 0 || valueCelsius == null
                    || affectedQuantity == null || affectedQuantity.signum() <= 0
                    || temperatureEvidenceId == null || evidenceObjectId == null || occurredAt == null) {
                throw new IllegalArgumentException("Preventive temperature hold request is incomplete");
            }
            if (minimumCelsius == null && maximumCelsius == null
                    || minimumCelsius != null && maximumCelsius != null
                    && minimumCelsius.compareTo(maximumCelsius) > 0
                    || (minimumCelsius == null || valueCelsius.compareTo(minimumCelsius) >= 0)
                    && (maximumCelsius == null || valueCelsius.compareTo(maximumCelsius) <= 0)) {
                throw new IllegalArgumentException("Preventive temperature hold requires an out-of-range reading");
            }
        }
    }

    record PreventiveHoldResult(UUID temperatureEvaluationId, UUID lotId, String lotStatus,
                                long resultingLotVersion, BigDecimal affectedQuantity,
                                boolean blocksCommittedExecution) {
        public PreventiveHoldResult(UUID temperatureEvaluationId, UUID lotId, String lotStatus,
                                    long resultingLotVersion, BigDecimal affectedQuantity) {
            this(temperatureEvaluationId, lotId, lotStatus, resultingLotVersion, affectedQuantity, true);
        }
    }

    record StockEvidenceHoldRequest(UUID lotId, UUID warehouseId, long expectedLotVersion,
                                    UUID temperatureEvidenceId, UUID sourceEvidenceId,
                                    BigDecimal valueCelsius, BigDecimal minimumCelsius,
                                    BigDecimal maximumCelsius, BigDecimal affectedQuantity,
                                    UUID evidenceObjectId, String reason, Instant occurredAt) {
        public StockEvidenceHoldRequest {
            if (lotId == null || warehouseId == null || expectedLotVersion < 0
                    || temperatureEvidenceId == null || valueCelsius == null
                    || affectedQuantity == null || affectedQuantity.signum() <= 0
                    || evidenceObjectId == null || reason == null || reason.isBlank()
                    || occurredAt == null) {
                throw new IllegalArgumentException("Stock temperature hold request is incomplete");
            }
            if (minimumCelsius == null && maximumCelsius == null
                    || minimumCelsius != null && maximumCelsius != null
                    && minimumCelsius.compareTo(maximumCelsius) > 0
                    || (minimumCelsius == null || valueCelsius.compareTo(minimumCelsius) >= 0)
                    && (maximumCelsius == null || valueCelsius.compareTo(maximumCelsius) <= 0)) {
                throw new IllegalArgumentException("Stock temperature hold requires an out-of-range reading");
            }
        }
    }

    record TemperatureEvaluationSnapshot(UUID evaluationId, UUID lotId, BigDecimal affectedQuantity,
                                         BigDecimal remainingHeldQuantity, String status,
                                         String disposition, UUID sourceEvidenceId, UUID evidenceObjectId,
                                         Long expectedLotVersion, boolean blocksCommittedExecution) { }
}
