package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseLogisticsFulfillmentFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reuses BC-05 reservation handoff rules on the exact routed Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantWarehouseLogisticsFulfillmentFactory
        implements TenantWarehouseLogisticsFulfillmentFactory {
    @Override
    public WarehouseLogisticsFulfillmentPort bindTo(JdbcTemplate tenantJdbc,
                                                     ChangeEventPersistencePort tenantChangeFeed,
                                                     InventoryCommercialSource tenantCommercialSource) {
        return new WarehouseLogisticsFulfillmentAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                Objects.requireNonNull(tenantChangeFeed, "Tenant change feed is required"),
                Objects.requireNonNull(tenantCommercialSource, "Tenant Sales source is required"));
    }
}
