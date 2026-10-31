package com.nexa.api.customerbuyerrelationships.presentation.communication.response;

import java.util.List;
import java.util.UUID;

public record BuyerSalesMessagePageResponse(List<BuyerSalesMessageResponse> items,
                                            UUID nextAfterMessageId, boolean hasMore) {
    public BuyerSalesMessagePageResponse {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
