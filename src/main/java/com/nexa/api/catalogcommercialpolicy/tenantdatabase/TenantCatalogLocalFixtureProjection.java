package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/** Operational startup projection for reviewed local Tenant fixtures; not a Catalog client contract. */
public interface TenantCatalogLocalFixtureProjection {
    void seedExactTenantWorkspace(JdbcTemplate tenantJdbc, UUID tenantId, UUID workspaceId);

    List<String> catalogItemIds();
}
