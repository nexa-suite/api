package com.nexa.api.salescommitment.application.salesorder.port;

import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.domain.model.salesorder.ManualSalesOrder;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderId;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderNumber;

import java.util.Optional;

public interface ManualSalesOrderPersistencePort {
    Optional<ManualSalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId,
                                                     String idempotencyKey, String requestHash);

    default Optional<ManualSalesOrderView> findById(String tenantId, String workspaceId, String salesOrderId) {
        return Optional.empty();
    }

    SalesOrderIdentity nextIdentity(String tenantId, String workspaceId);

    ManualSalesOrderView save(ManualSalesOrder order, String actorMembershipId, String idempotencyKey,
                              String requestHash, long nowEpochMillis);

    record SalesOrderIdentity(SalesOrderId id, SalesOrderNumber number) { }
}
