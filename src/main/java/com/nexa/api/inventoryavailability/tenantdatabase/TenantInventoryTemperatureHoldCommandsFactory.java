package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryTemperatureHoldCommands;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-05 temperature holds to the caller's Tenant session and verified BC-01 access snapshot. */
public interface TenantInventoryTemperatureHoldCommandsFactory {
    InventoryTemperatureHoldCommands bindTo(JdbcTemplate tenantJdbc,
                                             ChangeEventPersistencePort tenantChangeFeed,
                                             SellableSkuQuery tenantSellableSkus,
                                             InventoryCommercialSource tenantCommercialSource,
                                             InventoryFulfillmentSource tenantFulfillmentSource,
                                             WarehouseObjectAccess verifiedWarehouseAccess,
                                             BusinessEvidenceQuery tenantBusinessEvidence);
}
