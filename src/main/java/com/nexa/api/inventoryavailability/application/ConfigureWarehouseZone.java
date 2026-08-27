package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.port.WarehouseConfigurationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Profile("!test")
public class ConfigureWarehouseZone {
    private final WarehouseConfigurationPersistencePort persistence;

    public ConfigureWarehouseZone(WarehouseConfigurationPersistencePort persistence) { this.persistence = persistence; }

    @Transactional
    public WarehouseOperationsService.ZoneSummary create(CurrentAccessContext context, String warehouseId, String code,
                                                         String name, String type, BigDecimal min, BigDecimal max) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.createZone(context, warehouseId, code, name, type, min, max);
    }

    @Transactional
    public WarehouseOperationsService.ZoneSummary update(CurrentAccessContext context, String warehouseId, String zoneId,
                                                         String name, BigDecimal min, BigDecimal max, String status,
                                                         long expectedVersion) {
        WarehouseApplicationAuthorization.write(context);
        return persistence.updateZone(context, warehouseId, zoneId, name, min, max, status, expectedVersion);
    }
}
