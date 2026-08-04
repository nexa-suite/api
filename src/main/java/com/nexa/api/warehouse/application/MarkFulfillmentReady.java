package com.nexa.api.warehouse.application;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.port.WarehouseDashboardQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("!test")
public class MarkFulfillmentReady {
    private final WarehouseDashboardQueryPort query;

    public MarkFulfillmentReady(WarehouseDashboardQueryPort query) { this.query = query; }

    @Transactional(readOnly = true)
    public List<WarehouseOperationsService.ReadinessCandidate> execute(CurrentAccessContext context) {
        WarehouseApplicationAuthorization.fulfillmentRead(context);
        return query.readiness(context);
    }
}
