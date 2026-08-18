package com.nexa.api.catalogmanagement.infrastructure.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Reconciles the canonical ProductFamily/SellableSku projection after seed import. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogSkuPersistenceBootstrap {
    private final JdbcTemplate jdbc;
    private final Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings;
    private final Map<String, CatalogVariantMappingLoader.MappingItem> variantMappings;

    public CatalogSkuPersistenceBootstrap(JdbcTemplate jdbc, CatalogFamilySkuMappingLoader mappingLoader,
            CatalogVariantMappingLoader variantMappingLoader) {
        this.jdbc = jdbc;
        this.mappings = mappingLoader.byLegacyCatalogItemId();
        this.variantMappings = variantMappingLoader.byLegacyCatalogItemId();
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    @Transactional
    public void reconcile() {
        Instant now = Instant.now();
        jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.catalog_item_id,p.description,p.category_id,p.brand_id,p.storage_temperature,p.status,p.version,p.created_at,p.updated_at,pp.unit_of_measure,coalesce(pv.buyer_visible,true) visible from catalog_management.product p left join catalog_management.product_presentation pp on pp.product_id=p.id and pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id left join catalog_management.product_visibility pv on pv.product_id=p.id and pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id order by p.id",
                (rs, row) -> { reconcileProduct(rs, now); return null; });
    }

    private void reconcileProduct(ResultSet rs, Instant now) throws SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        UUID workspaceId = rs.getObject("workspace_id", UUID.class);
        String catalogItemId = rs.getString("catalog_item_id");
        CatalogFamilySkuMappingLoader.MappingItem mapping = mappings.get(catalogItemId);
        if (mapping == null) throw new IllegalStateException("No explicit catalog family/SKU mapping for " + catalogItemId);
        CatalogVariantMappingLoader.MappingItem variantMapping = variantMappings.get(catalogItemId);
        String familyCode = variantMapping == null ? mapping.familyCode() : variantMapping.familyCode();
        String familyName = variantMapping == null ? mapping.familyName() : variantMapping.familyName();

        UUID familyId = jdbc.query("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?",
                (r, n) -> r.getObject(1, UUID.class), tenantId, workspaceId, familyCode).stream().findFirst().orElse(null);
        if (familyId == null) {
            UUID candidate = UUID.nameUUIDFromBytes((tenantId + ":" + workspaceId + ":family:" + familyCode).getBytes(StandardCharsets.UTF_8));
            jdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,storage_family,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,family_code) do nothing",
                    candidate, tenantId, workspaceId, familyCode, familyName, rsSafe(rs, "description", ""), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class), rs.getString("storage_temperature"), rs.getString("status"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
            familyId = jdbc.queryForObject("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?", UUID.class, tenantId, workspaceId, familyCode);
        } else {
            jdbc.update("update catalog_management.product_family set name=?,updated_at=? where tenant_id=? and workspace_id=? and id=?", familyName, java.sql.Timestamp.from(now), tenantId, workspaceId, familyId);
        }

        UUID variantId = null;
        if (variantMapping != null) {
            variantId = UUID.nameUUIDFromBytes((tenantId + ":" + workspaceId + ":variant:" + variantMapping.variantCode()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("insert into catalog_management.product_variant (id,tenant_id,workspace_id,family_id,variant_code,name,description,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,'ACTIVE',0,?,?) on conflict (tenant_id,workspace_id,variant_code) do update set family_id=excluded.family_id,name=excluded.name,updated_at=excluded.updated_at",
                    variantId, tenantId, workspaceId, familyId, variantMapping.variantCode(), variantMapping.variantName(), "Variante comercial revisada de " + familyName, rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
            variantId = jdbc.queryForObject("select id from catalog_management.product_variant where tenant_id=? and workspace_id=? and variant_code=?", UUID.class, tenantId, workspaceId, variantMapping.variantCode());
        }

        UUID productId = rs.getObject("id", UUID.class);
        jdbc.update("insert into catalog_management.sellable_sku (id,tenant_id,workspace_id,family_id,variant_id,legacy_product_id,legacy_catalog_item_id,sku_code,presentation,packaging_type,unit_of_measure,pack_quantity,status,visible,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,legacy_catalog_item_id) do update set family_id=excluded.family_id,variant_id=excluded.variant_id,sku_code=excluded.sku_code,presentation=excluded.presentation,visible=excluded.visible,updated_at=excluded.updated_at",
                productId, tenantId, workspaceId, familyId, variantId, productId, catalogItemId, mapping.skuCode(), mapping.presentation(), "UNSPECIFIED", rs.getString("unit_of_measure") == null ? "UNIT" : rs.getString("unit_of_measure"), java.math.BigDecimal.ONE, rs.getString("status"), rs.getBoolean("visible"), rs.getLong("version"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
        jdbc.update("insert into catalog_management.sku_price (id,tenant_id,workspace_id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at,cancelled_at) select pp.id,pp.tenant_id,pp.workspace_id,pp.product_id,pp.amount,pp.currency,pp.valid_from,pp.valid_until,pp.source_code,pp.source_description,pp.version,pp.created_at,pp.cancelled_at from catalog_management.product_price pp where pp.tenant_id=? and pp.workspace_id=? and pp.product_id=? on conflict (id) do nothing", tenantId, workspaceId, productId);
        jdbc.update("insert into catalog_management.promotion_sku (promotion_id,tenant_id,workspace_id,sku_id) select promotion_id,tenant_id,workspace_id,product_id from catalog_management.promotion_product where tenant_id=? and workspace_id=? and product_id=? on conflict do nothing", tenantId, workspaceId, productId);
    }

    private static String rsSafe(ResultSet rs, String name, String fallback) throws SQLException {
        String value = rs.getString(name);
        return value == null ? fallback : value;
    }
}
