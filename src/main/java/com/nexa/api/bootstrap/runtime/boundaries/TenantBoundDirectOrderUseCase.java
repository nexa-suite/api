package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Runs Direct Order decisions on the exact Tenant transaction supplied by the router. */
public final class TenantBoundDirectOrderUseCase implements DirectOrderUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantSalesCommitmentCompositionProvider compositions;

    public TenantBoundDirectOrderUseCase(TenantBusinessDatabaseRouter router,
            TenantSalesCommitmentCompositionProvider compositions) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.compositions = Objects.requireNonNull(compositions, "Tenant Sales composition provider is required");
    }

    @Override
    public SalesOrderView create(CurrentAccessContext context, String clientAccountId, String priority,
            LocalDate requestedDeliveryDate, String deliverySnapshot, String paymentOption, String notes,
            List<Line> lines, String idempotencyKey) {
        return router.inTransaction(context, jdbc -> compositions.bindTo(jdbc).directOrders().create(context,
                clientAccountId, priority, requestedDeliveryDate, deliverySnapshot, paymentOption, notes,
                lines, idempotencyKey));
    }
}
