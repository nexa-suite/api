package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Paged Buyer membership links for one authorized Client Account.")
public record ClientAccountBuyerMembershipPageResponse(
        List<ClientAccountBuyerMembershipResponse> items,
        int page,
        int size,
        long total) {
    public ClientAccountBuyerMembershipPageResponse {
        items = List.copyOf(items);
    }
}
