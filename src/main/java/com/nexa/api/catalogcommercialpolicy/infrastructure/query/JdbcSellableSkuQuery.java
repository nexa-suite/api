package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcSellableSkuQuery implements SellableSkuQuery {
    private static final String SELECT = "select s.id,s.family_id,f.family_code,s.sku_code,s.legacy_catalog_item_id,f.name,"
            + "s.presentation,s.unit_of_measure,p.amount,p.currency from catalog_management.sellable_sku s "
            + "join catalog_management.product_family f on f.tenant_id=s.tenant_id and f.workspace_id=s.workspace_id and f.id=s.family_id "
            + "join lateral (select amount,currency from catalog_management.sku_price p0 where p0.tenant_id=s.tenant_id "
            + "and p0.workspace_id=s.workspace_id and p0.sku_id=s.id and p0.cancelled_at is null "
            + "and p0.valid_from<=current_timestamp and (p0.valid_until is null or p0.valid_until>current_timestamp) "
            + "order by p0.valid_from desc,p0.id limit 1) p on true "
            + "where s.tenant_id=? and s.workspace_id=? and s.status='ACTIVE' and s.visible and ";

    private final JdbcTemplate jdbc;

    public JdbcSellableSkuQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, UUID skuId) {
        return query(tenantId, workspaceId, "s.id=?", skuId);
    }

    @Override
    public Map<UUID, SellableSkuReference> findActive(UUID tenantId, UUID workspaceId, List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return Map.of();
        List<UUID> ids = skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = ids.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId));
        parameters.addAll(ids);
        Map<UUID, SellableSkuReference> result = new LinkedHashMap<>();
        jdbc.query(SELECT + "s.id in (" + placeholders + ")", rs -> {
            while (rs.next()) {
                SellableSkuReference reference = reference(rs);
                result.put(reference.skuId(), reference);
            }
            return null;
        }, parameters.toArray());
        return Map.copyOf(result);
    }

    @Override
    public Optional<SellableSkuReference> findActiveByLegacyCatalogItemId(
            UUID tenantId, UUID workspaceId, String legacyCatalogItemId) {
        return query(tenantId, workspaceId, "s.legacy_catalog_item_id=?", legacyCatalogItemId);
    }

    private Optional<SellableSkuReference> query(UUID tenantId, UUID workspaceId, String selector, Object value) {
        return jdbc.query(SELECT + selector,
                (rs, ignored) -> reference(rs), tenantId, workspaceId, value)
                .stream().findFirst();
    }

    private static SellableSkuReference reference(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SellableSkuReference(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getBigDecimal(9), rs.getString(10));
    }
}
