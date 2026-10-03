package com.nexa.api.fulfillmentdelivery.application.model;

import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomerInstructionModels {
    private CustomerInstructionModels() { }
    public record Instruction(UUID id, long instructionVersion, DeliveryInstructionKind kind, String content,
                              String sourceKind, String sourceReference, UUID recordedByMembershipId,
                              Instant recordedAt) { }
    public record Snapshot(UUID salesOrderId, long version, boolean editable, List<Instruction> instructions) {
        public Snapshot { instructions = List.copyOf(instructions); }
    }
    public record Publish(UUID tenantId, UUID workspaceId, UUID salesOrderId, UUID actorMembershipId,
                          UUID instructionId, long expectedVersion, DeliveryInstructionKind kind,
                          String content, String sourceKind, String sourceReference,
                          String idempotencyKey, String requestHash, Instant recordedAt, boolean salesOrderActive) { }
}
