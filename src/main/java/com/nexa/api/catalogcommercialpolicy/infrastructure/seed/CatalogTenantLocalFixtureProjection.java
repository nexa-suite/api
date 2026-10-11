package com.nexa.api.catalogcommercialpolicy.infrastructure.seed;

import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogLocalFixtureProjection;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Keeps local Catalog fixture import and SKU projection under the BC-03 owner. */
@Component
@Profile("local-fixtures")
public final class CatalogTenantLocalFixtureProjection implements TenantCatalogLocalFixtureProjection {
    private final CatalogPersistenceBootstrap catalogSeed;
    private final CatalogSkuPersistenceBootstrap skuSeed;
    private final CatalogFamilySkuMappingLoader mappings;

    public CatalogTenantLocalFixtureProjection(CatalogPersistenceBootstrap catalogSeed,
            CatalogSkuPersistenceBootstrap skuSeed, CatalogFamilySkuMappingLoader mappings) {
        this.catalogSeed = Objects.requireNonNull(catalogSeed);
        this.skuSeed = Objects.requireNonNull(skuSeed);
        this.mappings = Objects.requireNonNull(mappings);
    }

    @Override
    public void seedExactTenantWorkspace(JdbcTemplate tenantJdbc, UUID tenantId, UUID workspaceId) {
        catalogSeed.importExactTenantWorkspace(tenantJdbc, tenantId, workspaceId);
        skuSeed.reconcileExactTenantWorkspace(tenantJdbc, tenantId, workspaceId);
    }

    @Override
    public List<String> catalogItemIds() {
        return mappings.byLegacyCatalogItemId().keySet().stream().sorted().toList();
    }
}
