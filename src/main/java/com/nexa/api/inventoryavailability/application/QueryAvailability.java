package com.nexa.api.inventoryavailability.application;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.inventoryavailability.application.port.WarehouseInventoryPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("!test")
public class QueryAvailability {
    private final WarehouseInventoryPersistencePort persistence;

    public QueryAvailability(WarehouseInventoryPersistencePort persistence) { this.persistence = persistence; }

    @Transactional(readOnly = true)
    public List<WarehouseOperationsService.Availability> execute(CurrentAccessContext context, List<String> ids) {
        if (!context.allows(Permission.WAREHOUSE_READ) && !context.allows(Permission.CATALOG_READ)) {
            throw new WarehouseOperationsService.WarehouseException("FORBIDDEN", false);
        }
        return persistence.availability(context, ids);
    }
}
