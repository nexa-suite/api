package com.nexa.api.sales.infrastructure.seed;

import com.nexa.api.sales.application.purchaserequest.port.SellableSkuSnapshotLookupPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Reads one canonical SKU plus its current server price for Sales-owned snapshots. */
@Component
@Profile("!test")
public class SellableSkuSnapshotPersistenceAdapter implements SellableSkuSnapshotLookupPort {
    private final JdbcTemplate jdbc;

    public SellableSkuSnapshotPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Snapshot> findActive(UUID skuId, UUID tenantId, UUID workspaceId) {
        return jdbc.query("select s.id,s.family_id,f.family_code,s.sku_code,s.legacy_catalog_item_id,f.name,s.presentation,p.amount,p.currency "
                        + "from catalog_management.sellable_sku s "
                        + "join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
                        + "left join lateral (select amount,currency from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from<=current_timestamp and (p0.valid_until is null or p0.valid_until>current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true "
                        + "where s.tenant_id=? and s.workspace_id=? and s.id=? and s.status='ACTIVE' and s.visible",
                (rs, row) -> new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9)),
                tenantId, workspaceId, skuId).stream().filter(snapshot -> snapshot.price() != null && snapshot.currency() != null).findFirst();
    }

    @Override
    public Map<UUID, Snapshot> findActive(List<UUID> skuIds, UUID tenantId, UUID workspaceId) {
        if (skuIds == null || skuIds.isEmpty()) return Map.of();
        List<UUID> ids = skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        String placeholders = ids.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> parameters = new java.util.ArrayList<>(List.of(tenantId, workspaceId));
        parameters.addAll(ids);
        Map<UUID, Snapshot> result = new LinkedHashMap<>();
        jdbc.query("select s.id,s.family_id,f.family_code,s.sku_code,s.legacy_catalog_item_id,f.name,s.presentation,p.amount,p.currency "
                        + "from catalog_management.sellable_sku s "
                        + "join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
                        + "left join lateral (select amount,currency from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id and p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null and p0.valid_from<=current_timestamp and (p0.valid_until is null or p0.valid_until>current_timestamp) order by p0.valid_from desc,p0.id limit 1) p on true "
                        + "where s.tenant_id=? and s.workspace_id=? and s.id in (" + placeholders + ") and s.status='ACTIVE' and s.visible",
                rs -> {
                    while (rs.next()) {
                        Snapshot snapshot = new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9));
                        if (snapshot.price() != null && snapshot.currency() != null) result.put(snapshot.skuId(), snapshot);
                    }
                    return null;
                }, parameters.toArray());
        return Map.copyOf(result);
    }
}
