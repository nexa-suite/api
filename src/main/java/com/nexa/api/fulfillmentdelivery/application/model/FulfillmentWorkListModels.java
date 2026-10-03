package com.nexa.api.fulfillmentdelivery.application.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Scoped, read-only work references for the warehouse picking entry point. */
public final class FulfillmentWorkListModels {
    private FulfillmentWorkListModels() { }

    public record Page(List<Item> items, int page, int size, long totalItems, Instant asOf) {
        public Page {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record Item(UUID fulfillmentId, UUID salesOrderId, String status, long version,
                       UUID physicalAllocationId, long allocationVersion, int lineCount) { }
}
