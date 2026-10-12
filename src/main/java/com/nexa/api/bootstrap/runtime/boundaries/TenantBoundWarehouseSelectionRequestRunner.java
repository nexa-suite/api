package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.inventoryavailability.application.port.WarehouseSelectionRequestRunner;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;
import java.util.UUID;

/** Performs the bounded identity lookup inside the verified Tenant transaction. */
public final class TenantBoundWarehouseSelectionRequestRunner implements WarehouseSelectionRequestRunner {
    private final TenantBusinessDatabaseRouter router;
    private final TenantWarehouseSelectionQueryFactory queries;

    public TenantBoundWarehouseSelectionRequestRunner(TenantBusinessDatabaseRouter router,
                                                       TenantWarehouseSelectionQueryFactory queries) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.queries = Objects.requireNonNull(queries, "Tenant warehouse query factory is required");
    }

    @Override
    public boolean existsInScope(CurrentAccessContext context, UUID warehouseId) {
        Objects.requireNonNull(context, "Verified access context is required");
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        return router.inTransaction(context, jdbc -> Objects.requireNonNull(queries.bindTo(jdbc),
                "Tenant warehouse query factory returned no query").existsInScope(
                context.tenantId().value(), context.workspaceId().value(), warehouseId));
    }
}
