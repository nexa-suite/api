package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchRouteStartPort;
import com.nexa.api.fulfillmentdelivery.domain.dispatchorder.DispatchOrder;
import com.nexa.api.fulfillmentdelivery.domain.dispatchorder.DispatchStatus;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.inventoryavailability.application.port.WarehouseLogisticsFulfillmentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/** Owns the route-start transaction and its optimistic concurrency boundary. */
@Repository
@Profile("!test")
public class DispatchRouteStartPersistenceAdapter extends DispatchJdbcSupport implements DispatchRouteStartPort {
    public DispatchRouteStartPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                                WarehouseLogisticsFulfillmentPort warehouseFulfillment) {
        super(jdbc, changeFeed, warehouseFulfillment);
    }

    @Override
    @Transactional
    public Optional<LogisticsOperationsService.DispatchView> replayRouteStart(String tenantId, String workspaceId,
                                                                               String idempotencyKey, String requestHash) {
        return Optional.ofNullable(replay(uuid(tenantId), uuid(workspaceId), "dispatch-route-start",
                idempotencyKey, requestHash));
    }

    @Override
    @Transactional
    public Optional<DispatchOrder> findDispatchForRouteStart(String tenantId, String workspaceId, String dispatchId) {
        DispatchRow row = locked(uuid(tenantId), uuid(workspaceId), uuid(dispatchId), null);
        return row == null ? Optional.empty() : Optional.of(aggregate(row));
    }

    @Override
    @Transactional
    public LogisticsOperationsService.DispatchView commitRouteStart(String tenantId, String workspaceId,
                                                                     DispatchOrder aggregate, DispatchStatus expectedStatus,
                                                                     long expectedVersion, String actorMembershipId,
                                                                     String idempotencyKey, String requestHash,
                                                                     long nowEpochMillis) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID dispatchId = aggregate.id();
        UUID actor = uuid(actorMembershipId);
        DispatchRow row = locked(tenant, workspace, dispatchId, null);
        if (row == null) throw error("RESOURCE_NOT_FOUND", true);
        if (row.version() != expectedVersion || !expectedStatus.name().equals(row.status())) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        int changed = jdbc.update("update logistics.dispatch_order set status=?,updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and status=? and version=?",
                aggregate.status().name(), timestamp(nowEpochMillis), tenant, workspace, dispatchId,
                expectedStatus.name(), expectedVersion);
        if (changed != 1) throw error("CONCURRENCY_CONFLICT", false);
        appendEvent(tenant, workspace, dispatchId, "logistics.dispatch.route-started", expectedStatus.name(),
                aggregate.status().name(), actor, true, null, nowEpochMillis, row.clientAccountId());
        saveIdempotency(tenant, workspace, "dispatch-route-start", idempotencyKey, requestHash,
                dispatchId, nowEpochMillis);
        return detailView(tenantId, workspaceId, null, dispatchId.toString());
    }
}
