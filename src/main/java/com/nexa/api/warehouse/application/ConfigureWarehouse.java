package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseConfigurationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ConfigureWarehouse {
    private final WarehouseConfigurationPersistencePort persistence;

    public ConfigureWarehouse(WarehouseConfigurationPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.WarehouseSummary create(CurrentAccessContext context, String code, String name, String address) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.createWarehouse(context, code, name, address);
    }

    @Transactional
    public WarehouseOperationsService.WarehouseSummary update(CurrentAccessContext context, String id, String name,
                                                              String address, String status, long expectedVersion) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.updateWarehouse(context, id, name, address, status, expectedVersion);
    }
}
