package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.salescommitment.application.publicapi.SalesDocumentSourceQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-04's document source query to one router-owned Tenant session. */
@FunctionalInterface
public interface TenantSalesDocumentSourceQueryFactory {
    SalesDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc);
}
