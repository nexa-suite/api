package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancySubjectQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 discrepancy subject reads to Tenant storage and a preflighted BC-01 access snapshot. */
@FunctionalInterface
public interface TenantInboundReceivingDiscrepancySubjectQueryFactory {
    InboundReceivingDiscrepancySubjectQuery bindTo(JdbcTemplate tenantJdbc,
                                                    WarehouseObjectAccess verifiedWarehouseAccess);
}
