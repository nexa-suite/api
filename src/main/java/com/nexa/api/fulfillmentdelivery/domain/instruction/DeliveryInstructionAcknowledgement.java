package com.nexa.api.fulfillmentdelivery.domain.instruction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable acknowledgement of the exact instruction revision and content shown to a driver. */
public record DeliveryInstructionAcknowledgement(UUID instructionId, long instructionVersion,
                                                 DeliveryInstructionKind kind, String content,
                                                 UUID acknowledgedByMembershipId, Instant acknowledgedAt) {
    public DeliveryInstructionAcknowledgement {
        Objects.requireNonNull(instructionId, "Instruction ID is required");
        Objects.requireNonNull(kind, "Instruction kind is required");
        Objects.requireNonNull(content, "Acknowledged content is required");
        Objects.requireNonNull(acknowledgedByMembershipId, "Acknowledging actor is required");
        Objects.requireNonNull(acknowledgedAt, "Acknowledgement timestamp is required");
        if (instructionVersion < 1) throw new IllegalArgumentException("Instruction version must be positive");
        if (!kind.isCritical()) throw new IllegalArgumentException("Only critical instructions require acknowledgement");
    }
}
