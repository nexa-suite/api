package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogDocumentSourceQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-03's document source query to one router-owned Tenant session. */
@FunctionalInterface
public interface TenantCatalogDocumentSourceQueryFactory {
    CatalogDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc);
}
