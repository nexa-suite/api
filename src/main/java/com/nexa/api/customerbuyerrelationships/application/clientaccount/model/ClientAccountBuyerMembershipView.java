package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

import java.time.Instant;

/** Current account association for one personal Buyer membership. */
public record ClientAccountBuyerMembershipView(
        String membershipId,
        String status,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {
}
