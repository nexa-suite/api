package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 inbound discrepancy records to a routed Tenant session. */
@FunctionalInterface
public interface TenantInboundReceivingDiscrepancyCasesFactory {
    InboundReceivingDiscrepancyCases bindTo(JdbcTemplate tenantJdbc, WarehouseObjectAccess verifiedWarehouseAccess,
                                              SellableSkuQuery tenantSellableSkus);
}
