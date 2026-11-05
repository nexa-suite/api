package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.util.List;
import java.util.UUID;

/** Ordered page of immutable account-thread messages, suitable for HTTP polling. */
public record BuyerSalesMessagePage(List<BuyerSalesMessage> items, UUID nextAfterMessageId, boolean hasMore) {
    public BuyerSalesMessagePage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
