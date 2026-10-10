package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-06 document-source reads to one routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantFulfillmentDocumentSourceQueryFactory {
    FulfillmentDocumentSourceQuery bindTo(JdbcTemplate tenantJdbc);
}
