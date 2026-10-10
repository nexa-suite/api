package com.nexa.api.catalogcommercialpolicy.infrastructure.query;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogDocumentSourceQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogDocumentSourceQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates a BC-03 document source over the exact Tenant JDBC session supplied by the router. */
@Component
@Profile("!test")
public final class JdbcTenantCatalogDocumentSourceQueryFactory implements TenantCatalogDocumentSourceQueryFactory {
    @Override
    public CatalogDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcCatalogDocumentSourceQuery(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
