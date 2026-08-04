package com.nexa.api.catalogmanagement.infrastructure.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Reconciles the canonical SKU projection after the legacy seed importer. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogSkuPersistenceBootstrap {
    private final JdbcTemplate jdbc;
    private final Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings;

    public CatalogSkuPersistenceBootstrap(JdbcTemplate jdbc, CatalogFamilySkuMappingLoader mappingLoader) {
        this.jdbc = jdbc;
        this.mappings = mappingLoader.byLegacyCatalogItemId();
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.catalog_item_id,p.product_code,p.name,p.description,p.category_id,p.brand_id,p.storage_temperature,p.status,p.version,p.created_at,p.updated_at,pp.presentation,pp.unit_of_measure,pp.net_weight,coalesce(pv.buyer_visible,true) visible from catalog_management.product p left join catalog_management.product_presentation pp on pp.product_id=p.id left join catalog_management.product_visibility pv on pv.product_id=p.id order by p.id",
                (rs, row) -> { reconcileProduct(rs, now); return null; });
    }

    private void reconcileProduct(java.sql.ResultSet rs, Instant now) throws java.sql.SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        UUID workspaceId = rs.getObject("workspace_id", UUID.class);
        String catalogItemId = rs.getString("catalog_item_id");
        CatalogFamilySkuMappingLoader.MappingItem mapping = mappings.get(catalogItemId);
        if (mapping == null) throw new IllegalStateException("No explicit catalog family/SKU mapping for " + catalogItemId);
        String familyCode = mapping.familyCode();
        var familyIds = jdbc.query("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?", (r, n) -> r.getObject(1, UUID.class), tenantId, workspaceId, familyCode);
        UUID familyId;
        if (!familyIds.isEmpty()) {
            familyId = familyIds.get(0);
        } else {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,storage_family,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,family_code) do nothing",
                    id, tenantId, workspaceId, familyCode, "CATALOG FAMILY " + catalogItemId, rsSafe(rs, "description", ""), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class), rs.getString("storage_temperature"), rs.getString("status"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
            familyId = jdbc.queryForObject("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?", UUID.class, tenantId, workspaceId, familyCode);
        }
        jdbc.update("insert into catalog_management.sellable_sku (id,tenant_id,workspace_id,family_id,legacy_product_id,legacy_catalog_item_id,sku_code,presentation,packaging_type,unit_of_measure,net_weight,pack_quantity,status,visible,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,legacy_catalog_item_id) do update set family_id=excluded.family_id,sku_code=excluded.sku_code,presentation=excluded.presentation,visible=excluded.visible,updated_at=excluded.updated_at",
                rs.getObject("id", UUID.class), tenantId, workspaceId, familyId, rs.getObject("id", UUID.class), catalogItemId, mapping.skuCode(), mapping.presentation(), "UNSPECIFIED", rs.getString("unit_of_measure") == null ? "UNIT" : rs.getString("unit_of_measure"), rs.getBigDecimal("net_weight"), java.math.BigDecimal.ONE, rs.getString("status"), rs.getBoolean("visible"), rs.getLong("version"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
        jdbc.update("insert into catalog_management.sku_price (id,tenant_id,workspace_id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at,cancelled_at) select pp.id,pp.tenant_id,pp.workspace_id,pp.product_id,pp.amount,pp.currency,pp.valid_from,pp.valid_until,pp.source_code,pp.source_description,pp.version,pp.created_at,pp.cancelled_at from catalog_management.product_price pp where pp.tenant_id=? and pp.workspace_id=? and pp.product_id=? on conflict (id) do nothing",
                tenantId, workspaceId, rs.getObject("id", UUID.class));
        jdbc.update("insert into catalog_management.promotion_sku (promotion_id,tenant_id,workspace_id,sku_id) select promotion_id,tenant_id,workspace_id,product_id from catalog_management.promotion_product where tenant_id=? and workspace_id=? and product_id=? on conflict do nothing",
                tenantId, workspaceId, rs.getObject("id", UUID.class));
    }

    private static String rsSafe(java.sql.ResultSet rs, String name, String fallback) throws java.sql.SQLException {
        String value = rs.getString(name); return value == null ? fallback : value;
    }
}
