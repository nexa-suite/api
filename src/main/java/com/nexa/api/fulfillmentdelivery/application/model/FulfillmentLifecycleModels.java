package com.nexa.api.fulfillmentdelivery.application.model;

import com.nexa.api.fulfillmentdelivery.domain.model.delivery.DeliveryAttemptOutcome;

import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request values used by fulfillment and delivery application operations. */
public final class FulfillmentLifecycleModels {
    private FulfillmentLifecycleModels() { }

    @NamedInterface("fulfillment-public")
    public record PickingCommand(UUID pickerIdentityId, Instant startedAt, Instant completedAt,
                                 String notes, Long allocationVersion, List<PickingLine> lines) {
        public PickingCommand(UUID pickerIdentityId, Instant startedAt, Instant completedAt,
                              String notes, List<PickingLine> lines) {
            this(pickerIdentityId, startedAt, completedAt, notes, null, lines);
        }
        public PickingCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    @NamedInterface("fulfillment-public")
    public record PickingLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit,
                              UUID physicalAllocationLineId, UUID lotId, UUID warehouseId,
                              boolean fefoOverride, String fefoOverrideReason) {
        public PickingLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) {
            this(fulfillmentLineId, skuId, quantity, unit, null, null, null, false, null);
        }

        public PickingLine(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit,
                           UUID physicalAllocationLineId, UUID lotId, UUID warehouseId) {
            this(fulfillmentLineId, skuId, quantity, unit, physicalAllocationLineId, lotId, warehouseId, false, null);
        }
    }

    @NamedInterface("fulfillment-public")
    public record ShortageResolutionCommand(String reason, List<ShortageLineCommand> lines) {
        public ShortageResolutionCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    @NamedInterface("fulfillment-public")
    public record ShortageLineCommand(UUID fulfillmentLineId, UUID skuId, BigDecimal quantity, String unit) { }

    @NamedInterface("fulfillment-public")
    public record AttemptCommand(DeliveryAttemptOutcome outcome, String failureReason, String notes,
                                 Instant attemptedAt, List<AttemptLineCommand> lines) {
        public AttemptCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    @NamedInterface("fulfillment-public")
    public record AttemptLineCommand(UUID fulfillmentLineId, UUID skuId, BigDecimal attemptedQuantity,
                                     BigDecimal deliveredQuantity, BigDecimal rejectedQuantity,
                                     BigDecimal cancelledQuantity, String unit) {
        public AttemptLineCommand {
            deliveredQuantity = deliveredQuantity == null ? BigDecimal.ZERO : deliveredQuantity;
            rejectedQuantity = rejectedQuantity == null ? BigDecimal.ZERO : rejectedQuantity;
            cancelledQuantity = cancelledQuantity == null ? BigDecimal.ZERO : cancelledQuantity;
        }
    }

    @NamedInterface("fulfillment-public")
    public record PodCommand(String receiverName, Instant capturedAt, String notes,
                             UUID photoEvidenceObjectId, UUID signatureEvidenceObjectId) { }

    @NamedInterface("fulfillment-public")
    public record DriverProofCommand(String receiverName, Instant capturedAt, String notes) { }

    @NamedInterface("fulfillment-public")
    public record TemperatureCommand(UUID lotId, BigDecimal temperatureCelsius, String unit,
                                     String source, String evidenceMetadata, Instant recordedAt) { }

    @NamedInterface("fulfillment-public")
    public record TemperatureEvidenceCommand(String subjectType, UUID subjectId, BigDecimal value,
                                             String unit, Instant occurredAt, UUID evidenceObjectId,
                                             Long expectedLotVersion, BigDecimal affectedQuantity,
                                             String reason, UUID sourceEvidenceId) {
        public TemperatureEvidenceCommand(String subjectType, UUID subjectId, BigDecimal value,
                                          String unit, Instant occurredAt) {
            this(subjectType, subjectId, value, unit, occurredAt, null, null, null, null, null);
        }
    }

    @NamedInterface("fulfillment-public")
    public record FulfillmentTemperatureEvidenceCommand(UUID lotId, BigDecimal value,
                                                        String unit, Instant occurredAt,
                                                        Long expectedLotVersion, UUID evidenceObjectId) {
        public FulfillmentTemperatureEvidenceCommand(UUID lotId, BigDecimal value,
                                                    String unit, Instant occurredAt) {
            this(lotId, value, unit, occurredAt, null, null);
        }
    }
}
