package com.nexa.api.logistics.application.service;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.DispatchRouteStartPort;
import com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.logistics.domain.dispatchorder.DispatchOrder;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Application orchestrator for the dispatch route start use case. */
public final class StartDispatchRouteService {
    private static final String OPERATION = "dispatch-route-start";
    private final DispatchRouteStartPort routeStart;
    private final WarehouseLogisticsFulfillmentPort warehouse;

    public StartDispatchRouteService(DispatchRouteStartPort routeStart,
                                     WarehouseLogisticsFulfillmentPort warehouse) {
        this.routeStart = Objects.requireNonNull(routeStart, "Dispatch route-start persistence is required");
        this.warehouse = Objects.requireNonNull(warehouse, "Warehouse fulfillment port is required");
    }

    @Transactional
    public LogisticsOperationsService.DispatchView execute(
            String tenantId, String workspaceId, String dispatchId, long version,
            String actorMembershipId, String idempotencyKey, long now) {
        String requestHash = requestHash(dispatchId, version);
        var replay = routeStart.replayRouteStart(tenantId, workspaceId, idempotencyKey, requestHash);
        if (replay.isPresent()) return replay.get();

        DispatchOrder dispatch = routeStart.findDispatchForRouteStart(tenantId, workspaceId, dispatchId)
                .orElseThrow(() -> new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true));
        if (dispatch.version() != version) {
            throw new LogisticsOperationsService.LogisticsException("CONCURRENCY_CONFLICT", false);
        }
        DispatchStatus expectedStatus = dispatch.status();
        dispatch.startRoute();
        warehouse.consumeReservation(tenantId, workspaceId, dispatch.reservationId().value().toString(),
                actorMembershipId, dispatch.id().toString(), java.time.Instant.ofEpochMilli(now));
        return routeStart.commitRouteStart(tenantId, workspaceId, dispatch, expectedStatus, version, actorMembershipId,
                idempotencyKey, requestHash, now);
    }

    private static String requestHash(String dispatchId, long version) {
        String canonical = dispatchId.trim() + "|" + version;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
