package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.InventorySkuSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Prepared query input from Catalog's immutable batch policy snapshots. */
final class CatalogSkuSnapshots {
    private CatalogSkuSnapshots() { }

    static Input of(UUID tenantId, UUID workspaceId, List<InventorySkuSnapshot> snapshots) {
        List<Object> args = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (InventorySkuSnapshot snapshot : snapshots) {
            rows.add("(?::uuid,?::uuid,?::uuid,?::text,?::text,?::text,?::numeric,?::numeric)");
            args.add(snapshot.id()); args.add(tenantId); args.add(workspaceId);
            args.add(snapshot.legacyCatalogItemId()); args.add(snapshot.skuCode()); args.add(snapshot.status());
            args.add(snapshot.temperatureMin()); args.add(snapshot.temperatureMax());
        }
        String data = rows.isEmpty()
                ? "select null::uuid,null::uuid,null::uuid,null::text,null::text,null::text,null::numeric,null::numeric where false"
                : "values " + String.join(",", rows);
        return new Input("catalog_sku_snapshot(id,tenant_id,workspace_id,legacy_catalog_item_id,sku_code,status,temperature_min,temperature_max) as ("
                + data + ")", args);
    }

    record Input(String cte, List<Object> parameters) {
        Input { parameters = java.util.Collections.unmodifiableList(new ArrayList<>(parameters)); }
        Object[] prepend(Object... remaining) {
            List<Object> args = new ArrayList<>(parameters);
            args.addAll(java.util.Arrays.asList(remaining));
            return args.toArray();
        }
    }
}
