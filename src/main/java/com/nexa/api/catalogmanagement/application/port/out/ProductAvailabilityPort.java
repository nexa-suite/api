package com.nexa.api.catalogmanagement.application.port.out;

import com.nexa.api.catalogmanagement.application.model.CatalogScope;

import java.time.Instant;
import java.util.List;

public interface ProductAvailabilityPort {
    List<Snapshot> find(CatalogScope scope, List<String> catalogItemIds);

    record Snapshot(String catalogItemId, String status, boolean nearExpiry, Instant asOf) { }
}
