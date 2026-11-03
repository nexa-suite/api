package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable plain-text message in the Buyer and assigned Sales account thread. */
public record BuyerSalesMessage(UUID id, UUID clientAccountId, UUID buyerMembershipId, UUID senderMembershipId,
                                SenderKind senderKind, String body, Instant createdAt) {
    public BuyerSalesMessage {
        Objects.requireNonNull(id, "Message id is required");
        Objects.requireNonNull(clientAccountId, "Client Account id is required");
        Objects.requireNonNull(buyerMembershipId, "Buyer recipient membership id is required");
        Objects.requireNonNull(senderMembershipId, "Sender membership id is required");
        Objects.requireNonNull(senderKind, "Message sender kind is required");
        if (body == null || body.isBlank() || body.length() > 4000) {
            throw new IllegalArgumentException("Message body must contain 1 to 4000 characters");
        }
        Objects.requireNonNull(createdAt, "Message creation time is required");
    }

    public enum SenderKind { BUYER, SALES }
}
