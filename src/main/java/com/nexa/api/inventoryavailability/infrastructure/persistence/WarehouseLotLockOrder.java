package com.nexa.api.inventoryavailability.infrastructure.persistence;

/** Canonical ordering for every multi-row inventory-lot lock. */
final class WarehouseLotLockOrder {
    private WarehouseLotLockOrder() { }

    static String warehouse(String alias) {
        return alias + ".id";
    }

    static String storageZone(String alias) {
        return alias + ".warehouse_id," + alias + ".id";
    }

    static String inventoryLot(String alias) {
        return alias + ".sku_id," + alias + ".warehouse_id," + alias
                + ".expiration_date," + alias + ".received_at," + alias + ".id";
    }

    static String physicalAllocationLot(String allocationLineAlias, String lotAlias) {
        return allocationLineAlias + ".sku_id," + allocationLineAlias + ".warehouse_id," + lotAlias
                + ".expiration_date," + lotAlias + ".received_at," + allocationLineAlias + ".lot_id";
    }
}
