package com.nexa.api.logistics.application.port;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.domain.dispatchorder.DispatchOrder;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;

import java.util.Optional;

/** Transactional persistence boundary for route-start orchestration. */
public interface DispatchRouteStartPort {
    Optional<LogisticsOperationsService.DispatchView> replayRouteStart(
            String tenantId, String workspaceId, String idempotencyKey, String requestHash);

    Optional<DispatchOrder> findDispatchForRouteStart(
            String tenantId, String workspaceId, String dispatchId);

    LogisticsOperationsService.DispatchView commitRouteStart(
            String tenantId, String workspaceId, DispatchOrder aggregate,
            DispatchStatus expectedStatus, long expectedVersion,
            String actorMembershipId, String idempotencyKey, String requestHash, long nowEpochMillis);
}
