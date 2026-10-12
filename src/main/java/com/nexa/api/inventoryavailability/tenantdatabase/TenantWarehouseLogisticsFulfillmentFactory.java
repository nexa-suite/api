package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds the Warehouse reservation handoff to the same Tenant transaction as Logistics. */
public interface TenantWarehouseLogisticsFulfillmentFactory {
    WarehouseLogisticsFulfillmentPort bindTo(JdbcTemplate tenantJdbc,
                                              ChangeEventPersistencePort tenantChangeFeed,
                                              InventoryCommercialSource tenantCommercialSource);
}
