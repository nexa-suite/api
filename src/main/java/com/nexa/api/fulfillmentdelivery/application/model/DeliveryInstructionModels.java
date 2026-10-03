package com.nexa.api.fulfillmentdelivery.application.model;

import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transport-neutral BC-06 delivery-instruction views and commands. */
public final class DeliveryInstructionModels {
    private DeliveryInstructionModels() { }

    public record InstructionView(UUID id, DeliveryInstructionKind kind, String content,
                                  long instructionVersion, boolean critical, boolean acknowledged,
                                  Instant acknowledgedAt, UUID acknowledgedByMembershipId,
                                  String sourceKind, UUID recordedByMembershipId, Instant recordedAt) { }

    public record InstructionSetView(UUID deliveryId, long deliveryVersion, long instructionSetVersion,
                                     List<InstructionView> instructions) {
        public InstructionSetView {
            instructions = List.copyOf(instructions == null ? List.of() : instructions);
        }
    }

    public record AcknowledgementView(UUID instructionId, long instructionVersion,
                                      UUID acknowledgedByMembershipId, Instant acknowledgedAt) { }

    public record AcknowledgementResult(UUID deliveryId, long instructionSetVersion,
                                       List<AcknowledgementView> acknowledgements, boolean replayed) {
        public AcknowledgementResult {
            acknowledgements = List.copyOf(acknowledgements == null ? List.of() : acknowledgements);
        }
    }

    public record DispatchInstructionScope(UUID fulfillmentId) { }

    public record PublishRequest(UUID tenantId, UUID workspaceId, UUID deliveryId,
                                 UUID instructionId, DeliveryInstructionKind kind, String content,
                                 UUID actorMembershipId, long expectedDeliveryVersion,
                                 String idempotencyKey, String requestHash, Instant publishedAt) { }

    public record PublishedInstruction(UUID deliveryId, UUID instructionId, DeliveryInstructionKind kind,
                                       String content, long instructionVersion, boolean critical,
                                       long deliveryVersion, long instructionSetVersion, boolean replayed) { }

    public record AcknowledgeRequest(UUID tenantId, UUID workspaceId, UUID deliveryId,
                                     UUID actorMembershipId, long expectedInstructionSetVersion,
                                     List<UUID> instructionIds, String idempotencyKey,
                                     String requestHash, Instant acknowledgedAt) {
        public AcknowledgeRequest {
            instructionIds = List.copyOf(instructionIds == null ? List.of() : instructionIds);
        }
    }
}
