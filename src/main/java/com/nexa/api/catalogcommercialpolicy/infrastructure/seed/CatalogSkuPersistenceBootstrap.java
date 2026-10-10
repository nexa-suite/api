package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/** Reconciles the canonical ProductFamily/SellableSku projection after seed import. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogSkuPersistenceBootstrap {
    private final JdbcTemplate jdbc;
    private final com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory;
    private final Map<String, CatalogFamilySkuMappingLoader.MappingItem> mappings;
    private final Map<String, CatalogVariantMappingLoader.MappingItem> variantMappings;
    private final Environment environment;

    @org.springframework.beans.factory.annotation.Autowired
    public CatalogSkuPersistenceBootstrap(JdbcTemplate jdbc, CatalogFamilySkuMappingLoader mappingLoader,
            CatalogVariantMappingLoader variantMappingLoader, com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory,
            Environment environment) {
        this.workspaceDirectory = workspaceDirectory;
        this.jdbc = jdbc;
        this.mappings = mappingLoader.byLegacyCatalogItemId();
        this.variantMappings = variantMappingLoader.byLegacyCatalogItemId();
        this.environment = environment;
    }

    public CatalogSkuPersistenceBootstrap(JdbcTemplate jdbc, CatalogFamilySkuMappingLoader mappingLoader,
            CatalogVariantMappingLoader variantMappingLoader,
            com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory) {
        this(jdbc, mappingLoader, variantMappingLoader, workspaceDirectory, null);
    }

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    @Transactional
    public void reconcile() {
        if (environment != null && environment.acceptsProfiles(Profiles.of("local-fixtures"))) return;
        Instant now = Instant.now();
        UUID cursorTenant = null, cursorWorkspace = null;
        try {
            while (true) {
                var scopes = workspacePage(cursorTenant, cursorWorkspace);
                if (scopes.isEmpty()) break;
                for (var scope : scopes) {
                    Workspace workspace = new Workspace(scope.tenantId(), scope.workspaceId());
                setTransactionScope(workspace);
                jdbc.query("select p.id,p.tenant_id,p.workspace_id,p.catalog_item_id,p.description,p.category_id,p.brand_id,p.storage_temperature,p.status,p.version,p.created_at,p.updated_at,pp.unit_of_measure,coalesce(pv.buyer_visible,true) visible from catalog_management.product p left join catalog_management.product_presentation pp on pp.product_id=p.id and pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id left join catalog_management.product_visibility pv on pv.product_id=p.id and pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id where p.tenant_id=? and p.workspace_id=? order by p.id",
                    (rs, row) -> { reconcileProduct(jdbc, rs, now, false); return null; }, workspace.tenantId(), workspace.workspaceId());
            }
                var last = scopes.getLast();
                cursorTenant = last.tenantId(); cursorWorkspace = last.workspaceId();
                if (scopes.size() < 100) break;
            }
        } finally {
            clearTransactionScope();
        }
    }

    /** Reconciles only reviewed local fixture products in one explicit Tenant Workspace. */
    public void reconcileExactTenantWorkspace(JdbcTemplate targetJdbc, UUID tenantId, UUID workspaceId) {
        if (targetJdbc == null || tenantId == null || workspaceId == null) {
            throw new IllegalArgumentException("Exact Tenant and Workspace scope is required");
        }
        List<String> itemIds = mappings.keySet().stream().sorted().toList();
        List<String> familyCodes = itemIds.stream().map(itemId -> {
                    CatalogVariantMappingLoader.MappingItem variant = variantMappings.get(itemId);
                    return variant == null ? mappings.get(itemId).familyCode() : variant.familyCode();
                }).distinct().sorted().toList();
        List<String> variantCodes = variantMappings.values().stream().map(CatalogVariantMappingLoader.MappingItem::variantCode)
                .distinct().sorted().toList();
        int existingProducts = count(targetJdbc, "catalog_management.product", "catalog_item_id", tenantId, workspaceId, itemIds);
        if (existingProducts != itemIds.size()) {
            throw new IllegalStateException("Local catalog fixture must contain the complete reviewed product set");
        }
        int existingSkus = count(targetJdbc, "catalog_management.sellable_sku", "legacy_catalog_item_id", tenantId, workspaceId, itemIds);
        int existingFamilies = count(targetJdbc, "catalog_management.product_family", "family_code", tenantId, workspaceId, familyCodes);
        int existingVariants = count(targetJdbc, "catalog_management.product_variant", "variant_code", tenantId, workspaceId, variantCodes);
        if (existingSkus != 0 || existingFamilies != 0 || existingVariants != 0) {
            boolean completeProjection = existingSkus == itemIds.size()
                    && existingFamilies == familyCodes.size()
                    && existingVariants == variantCodes.size();
            if (!completeProjection) {
                throw new IllegalStateException("Existing catalog projection is partial; refusing to overwrite it");
            }
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        String sql = "select p.id,p.tenant_id,p.workspace_id,p.catalog_item_id,p.description,p.category_id,p.brand_id,"
                + "p.storage_temperature,p.status,p.version,p.created_at,p.updated_at,pp.unit_of_measure,"
                + "coalesce(pv.buyer_visible,true) visible from catalog_management.product p "
                + "left join catalog_management.product_presentation pp on pp.product_id=p.id and pp.tenant_id=p.tenant_id and pp.workspace_id=p.workspace_id "
                + "left join catalog_management.product_visibility pv on pv.product_id=p.id and pv.tenant_id=p.tenant_id and pv.workspace_id=p.workspace_id "
                + "where p.tenant_id=? and p.workspace_id=? and p.catalog_item_id in (" + placeholders + ") order by p.id";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(workspaceId);
        args.addAll(itemIds);
        Instant now = Instant.now();
        List<Boolean> reconciled = targetJdbc.query(sql, (rs, row) -> {
            reconcileProduct(targetJdbc, rs, now, true);
            return Boolean.TRUE;
        }, args.toArray());
        if (reconciled.size() != itemIds.size()) {
            throw new IllegalStateException("Local catalog fixture must contain the complete reviewed SKU set");
        }
    }

    private static int count(JdbcTemplate targetJdbc, String table, String codeColumn, UUID tenantId, UUID workspaceId,
            List<String> codes) {
        if (codes.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(workspaceId);
        args.addAll(codes);
        Integer result = targetJdbc.queryForObject("select count(*) from " + table + " where tenant_id=? and workspace_id=? and "
                + codeColumn + " in (" + placeholders + ")", Integer.class, args.toArray());
        return result == null ? 0 : result;
    }

    private List<com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory.Scope> workspacePage(UUID tenant, UUID workspace) {
        try {
            com.nexa.api.shared.context.RlsRequestScope.enableCrossScopeWorkspaceScan();
            return workspaceDirectory.scanAfter(tenant, workspace, 100);
        } finally {
            jdbc.queryForObject("select set_config('app.cross_scope_workspace_scan', '', true)", String.class);
            com.nexa.api.shared.context.RlsRequestScope.clear();
        }
    }

    private void setTransactionScope(Workspace workspace) {
        jdbc.queryForObject("select set_config('app.current_tenant_id', ?, true) || set_config('app.current_workspace_id', ?, true)",
                String.class, workspace.tenantId().toString(), workspace.workspaceId().toString());
    }

    private void clearTransactionScope() {
        jdbc.queryForObject("select set_config('app.current_tenant_id', '', true) || set_config('app.current_workspace_id', '', true)", String.class);
    }

    private void reconcileProduct(JdbcTemplate targetJdbc, ResultSet rs, Instant now, boolean exactTenantScope) throws SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        UUID workspaceId = rs.getObject("workspace_id", UUID.class);
        String catalogItemId = rs.getString("catalog_item_id");
        CatalogFamilySkuMappingLoader.MappingItem mapping = mappings.get(catalogItemId);
        if (mapping == null) throw new IllegalStateException("No explicit catalog family/SKU mapping for " + catalogItemId);
        CatalogVariantMappingLoader.MappingItem variantMapping = variantMappings.get(catalogItemId);
        String familyCode = variantMapping == null ? mapping.familyCode() : variantMapping.familyCode();
        String familyName = variantMapping == null ? mapping.familyName() : variantMapping.familyName();

        UUID expectedFamilyId = UUID.nameUUIDFromBytes((tenantId + ":" + workspaceId + ":family:" + familyCode).getBytes(StandardCharsets.UTF_8));
        UUID familyId;
        if (exactTenantScope) {
            targetJdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,storage_family,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,family_code) do nothing",
                    expectedFamilyId, tenantId, workspaceId, familyCode, familyName, rsSafe(rs, "description", ""), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class), rs.getString("storage_temperature"), rs.getString("status"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
            List<FamilyIdentity> families = targetJdbc.query("select id,name from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?",
                    (r, n) -> new FamilyIdentity(r.getObject("id", UUID.class), r.getString("name")), tenantId, workspaceId, familyCode);
            FamilyIdentity family = families.stream().findFirst().orElseThrow(() -> new IllegalStateException("Tenant catalog family was not created"));
            if (!familyName.equals(family.name())) {
                throw new IllegalStateException("Existing Tenant catalog family does not match reviewed fixture mapping");
            }
            familyId = family.id();
        } else {
            familyId = targetJdbc.query("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?",
                    (r, n) -> r.getObject(1, UUID.class), tenantId, workspaceId, familyCode).stream().findFirst().orElse(null);
            if (familyId == null) {
                targetJdbc.update("insert into catalog_management.product_family (id,tenant_id,workspace_id,family_code,name,description,category_id,brand_id,storage_family,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,family_code) do nothing",
                        expectedFamilyId, tenantId, workspaceId, familyCode, familyName, rsSafe(rs, "description", ""), rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class), rs.getString("storage_temperature"), rs.getString("status"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
                familyId = targetJdbc.queryForObject("select id from catalog_management.product_family where tenant_id=? and workspace_id=? and family_code=?", UUID.class, tenantId, workspaceId, familyCode);
            } else {
                targetJdbc.update("update catalog_management.product_family set name=?,updated_at=? where tenant_id=? and workspace_id=? and id=?", familyName, java.sql.Timestamp.from(now), tenantId, workspaceId, familyId);
            }
        }

        UUID variantId = null;
        if (variantMapping != null) {
            variantId = UUID.nameUUIDFromBytes((tenantId + ":" + workspaceId + ":variant:" + variantMapping.variantCode()).getBytes(StandardCharsets.UTF_8));
            targetJdbc.update("insert into catalog_management.product_variant (id,tenant_id,workspace_id,family_id,variant_code,name,description,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,'ACTIVE',0,?,?) "
                            + (exactTenantScope ? "on conflict (tenant_id,workspace_id,variant_code) do nothing" : "on conflict (tenant_id,workspace_id,variant_code) do update set family_id=excluded.family_id,name=excluded.name,updated_at=excluded.updated_at"),
                    variantId, tenantId, workspaceId, familyId, variantMapping.variantCode(), variantMapping.variantName(), "Variante comercial revisada de " + familyName, rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
            VariantIdentity variant = targetJdbc.query("select id,family_id,name,status from catalog_management.product_variant where tenant_id=? and workspace_id=? and variant_code=?",
                    (r, n) -> new VariantIdentity(r.getObject("id", UUID.class), r.getObject("family_id", UUID.class), r.getString("name"), r.getString("status")),
                    tenantId, workspaceId, variantMapping.variantCode()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Tenant catalog variant was not created"));
            if (exactTenantScope && (!variant.familyId().equals(familyId) || !variant.name().equals(variantMapping.variantName())
                    || !"ACTIVE".equals(variant.status()))) {
                throw new IllegalStateException("Existing Tenant catalog variant does not match reviewed fixture mapping");
            }
            variantId = variant.id();
        }

        UUID productId = rs.getObject("id", UUID.class);
        targetJdbc.update("insert into catalog_management.sellable_sku (id,tenant_id,workspace_id,family_id,variant_id,legacy_product_id,legacy_catalog_item_id,sku_code,presentation,packaging_type,unit_of_measure,pack_quantity,status,visible,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + (exactTenantScope ? "on conflict (tenant_id,workspace_id,legacy_catalog_item_id) do nothing" : "on conflict (tenant_id,workspace_id,legacy_catalog_item_id) do update set family_id=excluded.family_id,variant_id=excluded.variant_id,sku_code=excluded.sku_code,presentation=excluded.presentation,visible=excluded.visible,updated_at=excluded.updated_at"),
                productId, tenantId, workspaceId, familyId, variantId, productId, catalogItemId, mapping.skuCode(), mapping.presentation(), "UNSPECIFIED", rs.getString("unit_of_measure") == null ? "UNIT" : rs.getString("unit_of_measure"), java.math.BigDecimal.ONE, rs.getString("status"), rs.getBoolean("visible"), rs.getLong("version"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
        if (exactTenantScope) {
            SkuIdentity sku = targetJdbc.query("select id,family_id,variant_id,legacy_product_id,sku_code,presentation,packaging_type,unit_of_measure,pack_quantity,status,visible "
                            + "from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and legacy_catalog_item_id=?",
                    (r, n) -> new SkuIdentity(r.getObject("id", UUID.class), r.getObject("family_id", UUID.class),
                            r.getObject("variant_id", UUID.class), r.getObject("legacy_product_id", UUID.class),
                            r.getString("sku_code"), r.getString("presentation"), r.getString("packaging_type"), r.getString("unit_of_measure"),
                            r.getBigDecimal("pack_quantity"), r.getString("status"), r.getBoolean("visible")),
                    tenantId, workspaceId, catalogItemId).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Tenant catalog SKU was not created"));
            String unit = rs.getString("unit_of_measure") == null ? "UNIT" : rs.getString("unit_of_measure");
            if (!sku.id().equals(productId) || !sku.familyId().equals(familyId)
                    || !java.util.Objects.equals(sku.variantId(), variantId) || !sku.legacyProductId().equals(productId)
                    || !sku.skuCode().equals(mapping.skuCode()) || !sku.presentation().equals(mapping.presentation())
                    || !"UNSPECIFIED".equals(sku.packagingType()) || !sku.unitOfMeasure().equals(unit)
                    || sku.packQuantity().compareTo(java.math.BigDecimal.ONE) != 0
                    || !sku.status().equals(rs.getString("status")) || sku.visible() != rs.getBoolean("visible")) {
                throw new IllegalStateException("Existing Tenant catalog SKU does not match reviewed fixture mapping");
            }
        }
        targetJdbc.update("insert into catalog_management.sku_price (id,tenant_id,workspace_id,sku_id,amount,currency,valid_from,valid_until,source_code,source_description,version,created_at,cancelled_at) select pp.id,pp.tenant_id,pp.workspace_id,pp.product_id,pp.amount,pp.currency,pp.valid_from,pp.valid_until,pp.source_code,pp.source_description,pp.version,pp.created_at,pp.cancelled_at from catalog_management.product_price pp where pp.tenant_id=? and pp.workspace_id=? and pp.product_id=? on conflict (id) do nothing", tenantId, workspaceId, productId);
        targetJdbc.update("insert into catalog_management.promotion_sku (promotion_id,tenant_id,workspace_id,sku_id) select promotion_id,tenant_id,workspace_id,product_id from catalog_management.promotion_product where tenant_id=? and workspace_id=? and product_id=? on conflict do nothing", tenantId, workspaceId, productId);
    }

    private static String rsSafe(ResultSet rs, String name, String fallback) throws SQLException {
        String value = rs.getString(name);
        return value == null ? fallback : value;
    }

    private record Workspace(UUID tenantId, UUID workspaceId) { }
    private record FamilyIdentity(UUID id, String name) { }
    private record VariantIdentity(UUID id, UUID familyId, String name, String status) { }
    private record SkuIdentity(UUID id, UUID familyId, UUID variantId, UUID legacyProductId, String skuCode,
            String presentation, String packagingType, String unitOfMeasure,
            java.math.BigDecimal packQuantity, String status, boolean visible) { }
}
