package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.salescommitment.application.publicapi.SalesWorkflowEventSnapshotQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-04's event snapshot reads to one routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantSalesWorkflowEventSnapshotQueryFactory {
    SalesWorkflowEventSnapshotQuery bindTo(JdbcTemplate tenantJdbc);
}
