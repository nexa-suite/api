package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogDocumentSourceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Unfiltered, batched Catalog facts for document line enrichment. */
@Repository
@Profile("!test")
public class JdbcCatalogDocumentSourceQuery implements CatalogDocumentSourceQuery {
    private final JdbcTemplate jdbc;

    public JdbcCatalogDocumentSourceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Map<UUID, Sku> skus(UUID tenantId, UUID workspaceId, List<UUID> skuIds) {
        List<UUID> ids = distinctIds(skuIds);
        if (ids.isEmpty()) return Map.of();
        List<Object> args = new ArrayList<>(List.of(tenantId, workspaceId));
        args.addAll(ids);
        Map<UUID, Sku> result = new LinkedHashMap<>();
        jdbc.query("select id,family_id,sku_code,gross_weight from catalog_management.sellable_sku "
                        + "where tenant_id=? and workspace_id=? and id in (" + placeholders(ids.size()) + ")",
                (rs, row) -> new Sku(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class),
                        rs.getString("sku_code"), rs.getBigDecimal("gross_weight")), args.toArray())
                .forEach(sku -> result.put(sku.id(), sku));
        return Map.copyOf(result);
    }

    @Override
    public Map<UUID, String> familyNames(UUID tenantId, UUID workspaceId, List<UUID> familyIds) {
        List<UUID> ids = distinctIds(familyIds);
        if (ids.isEmpty()) return Map.of();
        List<Object> args = new ArrayList<>(List.of(tenantId, workspaceId));
        args.addAll(ids);
        Map<UUID, String> result = new LinkedHashMap<>();
        jdbc.query("select id,name from catalog_management.product_family "
                        + "where tenant_id=? and workspace_id=? and id in (" + placeholders(ids.size()) + ")",
                (rs, row) -> new FamilyName(rs.getObject("id", UUID.class), rs.getString("name")), args.toArray())
                .forEach(value -> result.put(value.id(), value.name()));
        return Collections.unmodifiableMap(result);
    }

    private record FamilyName(UUID id, String name) { }

    private static List<UUID> distinctIds(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
