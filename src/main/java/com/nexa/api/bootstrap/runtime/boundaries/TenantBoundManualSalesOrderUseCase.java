package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.salescommitment.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;

/** Runs the complete manual-order decision and persistence on one Tenant transaction. */
public final class TenantBoundManualSalesOrderUseCase implements ManualSalesOrderUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantSalesCommitmentCompositionProvider compositions;
    public TenantBoundManualSalesOrderUseCase(TenantBusinessDatabaseRouter router,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.compositions = Objects.requireNonNull(compositions, "Tenant Sales composition provider is required");
    }

    @Override
    public ManualSalesOrderView create(CurrentAccessContext context, CreateManualSalesOrderCommand command,
            String idempotencyKey) {
        return router.inTransaction(context, jdbc -> compositions.bindTo(jdbc).manualSalesOrders()
                .create(context, command, idempotencyKey));
    }
}
