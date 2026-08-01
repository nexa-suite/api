package com.nexa.api.sales.application.salesorder.port;

import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;

import java.util.Optional;

/** Persistence boundary for rehydrating and committing Sales Order aggregate decisions. */
public interface SalesOrderAggregatePersistencePort {
    Optional<SalesOrder> findForUpdate(String tenantId, String workspaceId, String salesOrderId);

    SalesOrderView saveTransition(SalesOrder aggregate, String action, String reason,
                                  String actorMembershipId, long expectedVersion, long nowEpochMillis);
}
