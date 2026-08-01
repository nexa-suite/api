package com.nexa.api.logistics.application.service;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.LogisticsPersistencePort;

/** Application orchestrator for the dispatch route start use case. */
public final class StartDispatchRouteService {
    private final LogisticsPersistencePort persistence;

    public StartDispatchRouteService(LogisticsPersistencePort persistence) {
        this.persistence = persistence;
    }

    public LogisticsOperationsService.DispatchView execute(
            String tenantId, String workspaceId, String dispatchId, long version,
            String actorMembershipId, String idempotencyKey, long now) {
        return persistence.startRoute(tenantId, workspaceId, dispatchId, version,
                actorMembershipId, idempotencyKey, now);
    }
}
