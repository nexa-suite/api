package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryTemperatureHoldCommands;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryTemperatureHoldCommandsFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05 temperature-hold rules on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantInventoryTemperatureHoldCommandsFactory
        implements TenantInventoryTemperatureHoldCommandsFactory {
    @Override
    public InventoryTemperatureHoldCommands bindTo(JdbcTemplate tenantJdbc,
                                                    ChangeEventPersistencePort tenantChangeFeed,
                                                    SellableSkuQuery tenantSellableSkus,
                                                    InventoryCommercialSource tenantCommercialSource,
                                                    InventoryFulfillmentSource tenantFulfillmentSource,
                                                    WarehouseObjectAccess verifiedWarehouseAccess,
                                                    BusinessEvidenceQuery tenantBusinessEvidence) {
        return new WarehouseInventoryPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantChangeFeed, "Tenant change feed is required"),
                Objects.requireNonNull(tenantSellableSkus, "Tenant Sellable SKU query is required"),
                null, null,
                Objects.requireNonNull(tenantCommercialSource, "Tenant Sales source is required"),
                Objects.requireNonNull(tenantFulfillmentSource, "Tenant fulfillment source is required"),
                Objects.requireNonNull(verifiedWarehouseAccess, "Preflight Warehouse access is required"),
                Objects.requireNonNull(tenantBusinessEvidence, "Tenant evidence query is required"));
    }
}
