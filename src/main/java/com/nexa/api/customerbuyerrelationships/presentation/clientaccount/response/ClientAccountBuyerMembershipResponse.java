package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Buyer membership link to a Client Account. Membership identity remains owned by Tenant Access.")
public record ClientAccountBuyerMembershipResponse(
        String membershipId,
        @Schema(allowableValues = {"ACTIVE", "INACTIVE"})
        String status,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {
}
