package com.nexa.api.salescommitment.application.salesorder.port;

import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.domain.model.salesorder.ApprovedPurchaseRequestSnapshot;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrder;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderId;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderNumber;

import java.util.Optional;

/** Infrastructure boundary for the conversion transaction; domain creation stays in the application service. */
public interface SalesOrderConversionPersistencePort {
    Optional<SalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId, String idempotencyKey);

    default Optional<SalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId,
                                                       String idempotencyKey, String requestHash) {
        return findByIdempotency(tenantId, workspaceId, actorMembershipId, idempotencyKey);
    }

    Optional<ApprovedPurchaseRequestSnapshot> loadApprovedSnapshot(String tenantId, String workspaceId,
                                                                    String purchaseRequestId, long expectedVersion);

    Optional<SalesOrderView> findBySourcePurchaseRequest(String tenantId, String workspaceId, String purchaseRequestId);

    SalesOrderIdentity nextIdentity(String tenantId, String workspaceId);

    SalesOrderView persistConversion(SalesOrder aggregate, long purchaseRequestVersion, String actorMembershipId,
                                      String idempotencyKey, String note, long nowEpochMillis);

    default SalesOrderView persistConversion(SalesOrder aggregate, long purchaseRequestVersion, String actorMembershipId,
                                             String idempotencyKey, String note, long nowEpochMillis, String requestHash) {
        return persistConversion(aggregate, purchaseRequestVersion, actorMembershipId, idempotencyKey, note, nowEpochMillis);
    }

    record SalesOrderIdentity(SalesOrderId id, SalesOrderNumber number) { }
}
