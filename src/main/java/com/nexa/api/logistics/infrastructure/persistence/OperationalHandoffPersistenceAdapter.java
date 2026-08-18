package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.OperationalHandoffNotificationPort;
import com.nexa.api.logistics.application.port.OperationalHandoffPort;
import com.nexa.api.logistics.domain.handoff.OperationalHandoffNote;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Owns the append-only Warehouse-to-Logistics handoff projection. */
@Repository
@Profile("!test")
public class OperationalHandoffPersistenceAdapter extends DispatchJdbcSupport implements OperationalHandoffPort {
    public OperationalHandoffPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                                WarehouseLogisticsFulfillmentPort warehouseFulfillment,
                                                OperationalHandoffNotificationPort handoffNotifications) {
        super(jdbc, changeFeed, warehouseFulfillment, handoffNotifications);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsService.HandoffNoteView> notes(String tenantId, String workspaceId,
                                                                  String clientAccountId, String dispatchId) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID dispatch = uuid(dispatchId);
        if (load(tenant, workspace, dispatch, clientAccountId == null ? null : uuid(clientAccountId), false) == null) {
            throw error("RESOURCE_NOT_FOUND", true);
        }
        String clientScope = clientAccountId == null ? "" : " and d.client_account_id=?";
        List<Object> args = new ArrayList<>(List.of(tenant, workspace, dispatch));
        if (clientAccountId != null) args.add(uuid(clientAccountId));
        return jdbc.query("select e.id,e.dispatch_order_id,e.reason,e.actor_membership_id,e.occurred_at,d.version " +
                        "from logistics.dispatch_event e join logistics.dispatch_order d " +
                        "on d.tenant_id=e.tenant_id and d.workspace_id=e.workspace_id and d.id=e.dispatch_order_id " +
                        "where e.tenant_id=? and e.workspace_id=? and e.dispatch_order_id=? " +
                        "and e.event_type='warehouse.logistics.handoff-note'" + clientScope +
                        " order by e.occurred_at asc,e.id asc",
                (rs, row) -> new LogisticsOperationsService.HandoffNoteView(rs.getObject("id").toString(),
                        rs.getObject("dispatch_order_id").toString(), rs.getString("reason"),
                        rs.getObject("actor_membership_id").toString(), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getLong("version")), args.toArray());
    }

    @Override
    @Transactional
    public LogisticsOperationsService.HandoffNoteView append(String tenantId, String workspaceId,
                                                              String dispatchId, long expectedVersion,
                                                              String actorMembershipId, String idempotencyKey,
                                                              String note, long now) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID dispatch = uuid(dispatchId);
        UUID actor = uuid(actorMembershipId);
        String requestHash = hash("dispatch-handoff-note", dispatchId, expectedVersion, note);
        LogisticsOperationsService.HandoffNoteView replay = replayHandoff(tenant, workspace, idempotencyKey, requestHash);
        if (replay != null) return replay;
        DispatchRow row = locked(tenant, workspace, dispatch, null);
        if (row == null) throw error("RESOURCE_NOT_FOUND", true);
        if (row.version() != expectedVersion) throw error("CONCURRENCY_CONFLICT", false);
        OperationalHandoffNote value = new OperationalHandoffNote(UUID.randomUUID(), dispatch, actor, note,
                Instant.ofEpochMilli(now), expectedVersion + 1);
        if (jdbc.update("update logistics.dispatch_order set updated_at=?,version=version+1 " +
                        "where tenant_id=? and workspace_id=? and id=? and version=?", timestamp(now), tenant,
                workspace, dispatch, expectedVersion) != 1) throw error("CONCURRENCY_CONFLICT", false);
        jdbc.update("insert into logistics.dispatch_event(id,tenant_id,workspace_id,dispatch_order_id,event_type," +
                        "from_status,to_status,actor_membership_id,buyer_visible,reason,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), tenant, workspace, dispatch, "warehouse.logistics.handoff-note", row.status(), row.status(),
                actor, false, value.note(), timestamp(now));
        handoffNotifications.notify(new OperationalHandoffNotificationPort.Notification(tenantId, workspaceId,
                row.clientAccountId().toString(), dispatchId, "warehouse.logistics.handoff-note", "HANDOFF_NOTE", now));
        saveIdempotency(tenant, workspace, "dispatch-handoff-note", idempotencyKey, requestHash, value.id(), now);
        return valueView(value);
    }
}
