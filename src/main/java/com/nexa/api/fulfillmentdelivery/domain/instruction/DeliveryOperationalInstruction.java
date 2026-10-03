package com.nexa.api.fulfillmentdelivery.domain.instruction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable revision of an operational instruction for one delivery. */
public record DeliveryOperationalInstruction(UUID revisionId, UUID instructionId, UUID deliveryId,
                                             DeliveryInstructionKind kind, String content,
                                             long instructionVersion, UUID authoredByMembershipId,
                                             Instant authoredAt) {
    public DeliveryOperationalInstruction {
        Objects.requireNonNull(revisionId, "Instruction revision ID is required");
        Objects.requireNonNull(instructionId, "Instruction ID is required");
        Objects.requireNonNull(deliveryId, "Delivery ID is required");
        Objects.requireNonNull(kind, "Instruction kind is required");
        Objects.requireNonNull(authoredByMembershipId, "Instruction author is required");
        Objects.requireNonNull(authoredAt, "Instruction timestamp is required");
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new IllegalArgumentException("Instruction content must contain 1 to 2000 characters");
        }
        if (instructionVersion < 1) throw new IllegalArgumentException("Instruction version must be positive");
        content = content.trim();
    }

    public boolean critical() {
        return kind.isCritical();
    }
}
