package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 physical allocation work to the caller's Tenant JDBC session and preflight access snapshot. */
public interface TenantPhysicalAllocationCommandsFactory {
    PhysicalAllocationCommands bindTo(JdbcTemplate tenantJdbc,
                                      BusinessTraceabilityCommands tenantTraceability,
                                      InventoryCommercialSource tenantCommercialSource,
                                      InventoryFulfillmentSource tenantFulfillmentSource,
                                      SellableSkuQuery tenantSellableSkus,
                                      CanonicalOutboxPort tenantCanonicalOutbox,
                                      WarehouseObjectAccess verifiedWarehouseAccess);
}
