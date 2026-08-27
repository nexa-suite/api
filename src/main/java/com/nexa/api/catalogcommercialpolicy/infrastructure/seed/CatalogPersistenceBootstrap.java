package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogPersistenceBootstrap {
    private static final String SEED_VERSION = "v1";
    private final JdbcTemplate jdbc;
    private final CatalogSeedLoader seedLoader;

    public CatalogPersistenceBootstrap(JdbcTemplate jdbc, CatalogSeedLoader seedLoader) {
        this.jdbc = jdbc;
        this.seedLoader = seedLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 20)
    @Transactional
    public void importDeterministicSeed() {
        List<Workspace> workspaces = jdbc.query("select t.id tenant_id,w.id workspace_id from tenant_management.tenant t join tenant_management.workspace w on w.tenant_id=t.id where t.status='ACTIVE' and w.status='ACTIVE' order by t.id,w.id",
                (rs, row) -> new Workspace(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        if (workspaces.isEmpty()) return;
        List<CatalogSeedItemRecord> seeds = seedLoader.load();
        Instant now = Instant.now();
        for (Workspace workspace : workspaces) importWorkspace(workspace, seeds, now);
    }

    private void importWorkspace(Workspace workspace, List<CatalogSeedItemRecord> seeds, Instant now) {
        int claimed = jdbc.update("insert into catalog_management.seed_import_history (tenant_id,workspace_id,seed_version,seed_checksum,imported_at) values (?,?,?,?,?) on conflict (tenant_id,workspace_id,seed_version) do nothing",
                workspace.tenantId(), workspace.workspaceId(), SEED_VERSION, CatalogSeedValidator.EXPECTED_SHA256, timestamp(now));
        if (claimed == 0) return;
        Map<String, UUID> categories = new HashMap<>();
        Map<String, UUID> brands = new HashMap<>();
        for (CatalogSeedItemRecord seed : seeds) {
            categories.computeIfAbsent(seed.categoryName(), key -> category(workspace, key, now));
            brands.computeIfAbsent(seed.brandName(), key -> brand(workspace, key, now));
        }
        for (CatalogSeedItemRecord seed : seeds) {
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
                    persistedProduct, workspace.tenantId(), workspace.workspaceId(), true, true, true, true, timestamp(now));
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
    private record Workspace(UUID tenantId, UUID workspaceId) { }
}
