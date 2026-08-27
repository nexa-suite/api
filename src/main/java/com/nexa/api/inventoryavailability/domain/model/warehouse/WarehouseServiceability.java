package com.nexa.api.inventoryavailability.domain.model.warehouse;

/** Buyer-facing serviceability projection; internal lifecycle status is not exposed. */
public record WarehouseServiceability(boolean serviceable) { }
