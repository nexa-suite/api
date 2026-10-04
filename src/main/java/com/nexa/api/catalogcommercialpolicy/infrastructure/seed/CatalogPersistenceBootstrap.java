package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogPersistenceBootstrap {
    private final JdbcTemplate jdbc;
    private final com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory;
    private final Supplier<List<CatalogPersistenceSeedItemRecord>> seedSupplier;
    private final String seedVersion;
    private final String seedChecksum;

    @Autowired
    public CatalogPersistenceBootstrap(JdbcTemplate jdbc, CatalogPersistenceSeedLoader seedLoader,
            com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory) {
        this(jdbc, seedLoader::load, "v2", CatalogPersistenceSeedValidator.EXPECTED_SHA256, workspaceDirectory);
    }

    /**
     * Compatibility constructor for the RLS isolation harness, which deliberately
     * exercises the frozen v1 seed in a manually created bootstrap.
     */
    public CatalogPersistenceBootstrap(JdbcTemplate jdbc, CatalogSeedLoader seedLoader,
            com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory) {
        this(jdbc, () -> seedLoader.load().stream().map(CatalogPersistenceBootstrap::adaptLegacySeed).toList(),
                "v1", CatalogSeedValidator.EXPECTED_SHA256, workspaceDirectory);
    }

    private CatalogPersistenceBootstrap(JdbcTemplate jdbc, Supplier<List<CatalogPersistenceSeedItemRecord>> seedSupplier,
            String seedVersion, String seedChecksum,
            com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
        this.jdbc = jdbc;
        this.seedSupplier = seedSupplier;
        this.seedVersion = seedVersion;
        this.seedChecksum = seedChecksum;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 20)
    @Transactional
    public void importDeterministicSeed() {
        List<CatalogPersistenceSeedItemRecord> seeds = null;
        Instant now = Instant.now();
        UUID cursorTenant = null, cursorWorkspace = null;
        try {
            while (true) {
                var scopes = workspacePage(cursorTenant, cursorWorkspace);
                if (scopes.isEmpty()) break;
                if (seeds == null) seeds = seedSupplier.get();
                for (var scope : scopes) {
                    Workspace workspace = new Workspace(scope.tenantId(), scope.workspaceId());
                    setTransactionScope(workspace);
                    importWorkspace(workspace, seeds, now);
                }
                var last = scopes.getLast();
                cursorTenant = last.tenantId(); cursorWorkspace = last.workspaceId();
                if (scopes.size() < 100) break;
            }
        } finally {
            clearTransactionScope();
        }
    }

    private void importWorkspace(Workspace workspace, List<CatalogPersistenceSeedItemRecord> seeds, Instant now) {
        int claimed = jdbc.update("insert into catalog_management.seed_import_history (tenant_id,workspace_id,seed_version,seed_checksum,imported_at) values (?,?,?,?,?) on conflict (tenant_id,workspace_id,seed_version) do nothing",
                workspace.tenantId(), workspace.workspaceId(), seedVersion, seedChecksum, timestamp(now));
        if (claimed == 0) return;
        Map<String, UUID> categories = new HashMap<>();
        Map<String, UUID> brands = new HashMap<>();
        for (CatalogPersistenceSeedItemRecord seed : seeds) {
            categories.computeIfAbsent(seed.categoryName(), key -> category(workspace, key, now));
            brands.computeIfAbsent(seed.brandName(), key -> brand(workspace, key, now));
        }
        for (CatalogPersistenceSeedItemRecord seed : seeds) {
            UUID productId = UUID.nameUUIDFromBytes((workspace.tenantId() + ":" + workspace.workspaceId() + ":product:" + seed.productId()).getBytes(StandardCharsets.UTF_8));
            String slug = slug(seed.itemName()) + "-" + seed.catalogItemId().toLowerCase(java.util.Locale.ROOT);
            jdbc.update("insert into catalog_management.product (id,tenant_id,workspace_id,catalog_item_id,product_code,slug,name,description,category_id,brand_id,storage_temperature,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?) on conflict (tenant_id,workspace_id,catalog_item_id) do nothing",
                    productId, workspace.tenantId(), workspace.workspaceId(), seed.catalogItemId(), seed.productId(), slug, seed.itemName(), seed.description(),
                    categories.get(seed.categoryName()), brands.get(seed.brandName()), temperature(seed.coldChainRequirement()), timestamp(now), timestamp(now));
            UUID persistedProduct = jdbc.queryForObject("select id from catalog_management.product where tenant_id=? and workspace_id=? and catalog_item_id=?", UUID.class,
                    workspace.tenantId(), workspace.workspaceId(), seed.catalogItemId());
            jdbc.update("insert into catalog_management.product_presentation (product_id,tenant_id,workspace_id,presentation,unit_of_measure,version,updated_at) values (?,?,?,?,?,0,?) on conflict (product_id) do nothing",
                    persistedProduct, workspace.tenantId(), workspace.workspaceId(), seed.presentation(), "UNIT", timestamp(now));
            jdbc.update("insert into catalog_management.product_visibility (product_id,tenant_id,workspace_id,buyer_visible,sales_visible,warehouse_visible,logistics_visible,version,updated_at) values (?,?,?,?,?,?,?,0,?) on conflict (product_id) do nothing",
                    persistedProduct, workspace.tenantId(), workspace.workspaceId(), seed.buyerVisible(), true, true, true, timestamp(now));
            jdbc.update("insert into catalog_management.product_asset_reference (id,tenant_id,workspace_id,product_id,asset_path,file_name,alt_text,sort_order) values (?,?,?,?,?,?,?,0) on conflict (tenant_id,workspace_id,product_id,asset_path) do nothing",
                    UUID.nameUUIDFromBytes((workspace.tenantId() + ":" + workspace.workspaceId() + ":asset:" + seed.catalogItemId() + ":" + seed.imageFileName()).getBytes(StandardCharsets.UTF_8)),
                    workspace.tenantId(), workspace.workspaceId(), persistedProduct, seed.imageUrl(), seed.imageFileName(), seed.itemName());
            Integer priceCount = jdbc.queryForObject("select count(*) from catalog_management.product_price where tenant_id=? and workspace_id=? and product_id=? and source_code=? and cancelled_at is null", Integer.class,
                    workspace.tenantId(), workspace.workspaceId(), persistedProduct, seed.sourcePriceCode());
            if (priceCount == null || priceCount == 0) {
                jdbc.update("insert into catalog_management.product_price (id,tenant_id,workspace_id,product_id,amount,currency,valid_from,source_code,source_description,version,created_at) values (?,?,?,?,?,?,?,?,?,0,?)",
                        UUID.nameUUIDFromBytes((workspace.tenantId() + ":" + workspace.workspaceId() + ":price:" + seed.catalogItemId() + ":" + seed.sourcePriceCode()).getBytes(StandardCharsets.UTF_8)),
                        workspace.tenantId(), workspace.workspaceId(), persistedProduct, seed.unitPriceAmount(), seed.unitPriceCurrency(), Timestamp.valueOf("2020-01-01 00:00:00"),
                        seed.sourcePriceCode(), seed.sourcePriceDescription(), timestamp(now));
            }
        }
    }

    private UUID category(Workspace workspace, String name, Instant now) {
        String slug = slug(name);
        UUID id = UUID.nameUUIDFromBytes((workspace.tenantId() + ":" + workspace.workspaceId() + ":category:" + slug).getBytes(StandardCharsets.UTF_8));
        jdbc.update("insert into catalog_management.category (id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values (?,?,?,?,?,'ACTIVE',0,?,?) on conflict (tenant_id,workspace_id,slug) do nothing",
                id, workspace.tenantId(), workspace.workspaceId(), slug, name, timestamp(now), timestamp(now));
        return jdbc.queryForObject("select id from catalog_management.category where tenant_id=? and workspace_id=? and slug=?", UUID.class,
                workspace.tenantId(), workspace.workspaceId(), slug);
    }

    private UUID brand(Workspace workspace, String name, Instant now) {
        String slug = slug(name);
        UUID id = UUID.nameUUIDFromBytes((workspace.tenantId() + ":" + workspace.workspaceId() + ":brand:" + slug).getBytes(StandardCharsets.UTF_8));
        jdbc.update("insert into catalog_management.brand (id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values (?,?,?,?,?,'ACTIVE',0,?,?) on conflict (tenant_id,workspace_id,slug) do nothing",
                id, workspace.tenantId(), workspace.workspaceId(), slug, name, timestamp(now), timestamp(now));
        return jdbc.queryForObject("select id from catalog_management.brand where tenant_id=? and workspace_id=? and slug=?", UUID.class,
                workspace.tenantId(), workspace.workspaceId(), slug);
    }

    private static String temperature(String value) {
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FROZEN" -> "FROZEN";
            case "NONE", "AMBIENT" -> "AMBIENT";
            default -> "REFRIGERATED";
        };
    }

    private static String slug(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private static CatalogPersistenceSeedItemRecord adaptLegacySeed(CatalogSeedItemRecord seed) {
        return new CatalogPersistenceSeedItemRecord(
                seed.catalogItemId(), seed.productId(), seed.itemName(), seed.brandName(), seed.categoryName(),
                seed.description(), seed.unitPriceAmount(), seed.unitPriceCurrency(), seed.coldChainRequirement(),
                seed.imageUrl(), seed.imageFileName(), seed.presentation(), seed.sourcePriceCode(),
                seed.sourcePriceDescription(), true, false);
    }

    private List<com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory.Scope> workspacePage(UUID tenant, UUID workspace) {
        try {
            com.nexa.api.shared.context.RlsRequestScope.enableCrossScopeWorkspaceScan();
            return workspaceDirectory.scanActiveAfter(tenant, workspace, 100);
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
    private record Workspace(UUID tenantId, UUID workspaceId) { }
}
