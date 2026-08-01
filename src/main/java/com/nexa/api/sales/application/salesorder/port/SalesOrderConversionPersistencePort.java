package com.nexa.api.sales.application.salesorder.port;

import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.domain.model.salesorder.ApprovedPurchaseRequestSnapshot;
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderNumber;

import java.util.Optional;

/** Infrastructure boundary for the conversion transaction; domain creation stays in the application service. */
public interface SalesOrderConversionPersistencePort {
    Optional<SalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId, String idempotencyKey);

    Optional<ApprovedPurchaseRequestSnapshot> loadApprovedSnapshot(String tenantId, String workspaceId,
                                                                    String purchaseRequestId, long expectedVersion);

    Optional<SalesOrderView> findBySourcePurchaseRequest(String tenantId, String workspaceId, String purchaseRequestId);

    SalesOrderIdentity nextIdentity(String tenantId, String workspaceId);

    SalesOrderView persistConversion(SalesOrder aggregate, long purchaseRequestVersion, String actorMembershipId,
                                      String idempotencyKey, String note, long nowEpochMillis);

    record SalesOrderIdentity(SalesOrderId id, SalesOrderNumber number) { }
}
