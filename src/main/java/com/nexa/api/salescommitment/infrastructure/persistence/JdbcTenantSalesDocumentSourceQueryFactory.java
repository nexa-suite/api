package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesDocumentSourceQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesDocumentSourceQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates a BC-04 document source over the exact Tenant JDBC session supplied by the router. */
@Component
@Profile("!test")
public final class JdbcTenantSalesDocumentSourceQueryFactory implements TenantSalesDocumentSourceQueryFactory {
    @Override
    public SalesDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc) {
        return new JdbcSalesDocumentSourceQuery(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
