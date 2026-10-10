package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds all BC-06 services from the same routed Tenant JDBC callback and verified central preflight snapshots. */
@FunctionalInterface
public interface TenantFulfillmentDeliveryCompositionFactory {
    FulfillmentDeliveryComposition bindTo(JdbcTemplate tenantJdbc,
                                          WarehouseObjectAccess verifiedWarehouseAccess,
                                          WorkforceDirectory verifiedWorkforce);
}
