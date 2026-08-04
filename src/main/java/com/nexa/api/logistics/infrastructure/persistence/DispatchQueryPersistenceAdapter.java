package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.DispatchQueryPersistencePort;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;
import com.nexa.api.logistics.domain.proofofdelivery.ProofOfDeliveryStatus;
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

@Repository
@Profile("!test")
public class DispatchQueryPersistenceAdapter extends DispatchJdbcSupport implements DispatchQueryPersistencePort {
    public DispatchQueryPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                           WarehouseLogisticsFulfillmentPort warehouseFulfillment) {
        super(jdbc, changeFeed, warehouseFulfillment);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.Page<LogisticsOperationsService.DispatchView> list(
            String tenantId, String workspaceId, String clientAccountId, String status,
            int page, int size, String sort) {
        pageCheck(page, size);
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        String where = " where d.tenant_id=? and d.workspace_id=?";
        if (clientAccountId != null) {
            where += " and d.client_account_id=?";
            args.add(uuid(clientAccountId));
        }
        if (status != null && !status.isBlank()) {
            where += " and d.status=?";
            args.add(enumValue(status, "status", DispatchStatus.values()));
        }
        long total = jdbc.queryForObject("select count(*) from logistics.dispatch_order d" + where,
                Long.class, args.toArray());
        String order = sort(sort, "updatedAt", "d.updated_at desc,d.id desc",
                "dispatchNumber", "d.dispatch_number asc,d.id asc",
                "deliveryWindowStart", "d.delivery_window_start asc nulls last,d.id asc",
                "priority", "d.priority asc,d.delivery_window_start asc nulls last,d.id asc",
                "status", "d.status asc,d.id asc");
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<LogisticsOperationsService.DispatchView> items = jdbc.query(
                selectSql() + where + " order by " + order + " limit ? offset ?",
                (rs, row) -> view(read(rs), clientAccountId != null), pageArgs.toArray());
        return new LogisticsOperationsService.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.DispatchView detail(String tenantId, String workspaceId,
                                                           String clientAccountId, String dispatchId) {
        return detailView(tenantId, workspaceId, clientAccountId, dispatchId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsService.DispatchEventView> events(String tenantId, String workspaceId,
                                                                      String clientAccountId, String dispatchId) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID dispatch = uuid(dispatchId);
        if (load(tenant, workspace, dispatch, clientAccountId == null ? null : uuid(clientAccountId), false) == null) {
            throw error("RESOURCE_NOT_FOUND", true);
        }
        String visibility = clientAccountId == null ? "" : " and buyer_visible=true";
        return jdbc.query("select id,event_type,from_status,to_status,occurred_at,buyer_visible " +
                        "from logistics.dispatch_event where tenant_id=? and workspace_id=? and dispatch_order_id=?" +
                        visibility + " order by occurred_at,id",
                (rs, row) -> {
                    String type = rs.getString(2);
                    boolean buyer = clientAccountId != null;
                    return new LogisticsOperationsService.DispatchEventView(rs.getObject(1).toString(),
                            buyer ? buyerEvent(type) : type, buyer ? null : rs.getString(3),
                            buyer ? null : rs.getString(4), rs.getTimestamp(5).toInstant().toString(),
                            rs.getBoolean(6), buyer ? buyerEvent(type) : rs.getString(4));
                }, tenant, workspace, dispatch);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.DashboardView dashboard(String tenantId, String workspaceId) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        Long[] values = jdbc.queryForObject(
                "select count(*) filter (where status='READY_FOR_OPERATIONS')," +
                        "count(*) filter (where status='PREPARING')," +
                        "count(*) filter (where status='ASSIGNED')," +
                        "count(*) filter (where status='SCHEDULED')," +
                        "count(*) filter (where status='READY_FOR_ROUTE')," +
                        "count(*) filter (where status='IN_ROUTE')," +
                        "count(*) filter (where status='INCIDENT')," +
                        "count(*) filter (where status='DELIVERED' and updated_at>=current_date)," +
                        "count(*) filter (where temperature_status='OUT_OF_RANGE')," +
                        "count(*) filter (where status='IN_ROUTE' and not exists(" +
                        "select 1 from logistics.proof_of_delivery p where p.dispatch_order_id=d.id))," +
                        "0::bigint from logistics.dispatch_order d where tenant_id=? and workspace_id=?",
                (rs, row) -> {
                    Long[] result = new Long[11];
                    for (int i = 0; i < result.length; i++) result[i] = rs.getLong(i + 1);
                    return result;
                }, tenant, workspace);
        long reservations = warehouseFulfillment.countReadyReservations(tenantId, workspaceId, Instant.now());
        return new LogisticsOperationsService.DashboardView(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9], reservations);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.AnalyticsView analytics(String tenantId, String workspaceId,
                                                               Instant from, Instant to) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        String sql = "select count(*),count(*) filter(where status='DELIVERED')," +
                "count(*) filter(where status='INCIDENT')," +
                "(select count(*) from logistics.temperature_reading t where t.tenant_id=? and t.workspace_id=? " +
                "and t.recorded_at>=? and t.recorded_at<? and t.status='OUT_OF_RANGE')," +
                "(select count(*) from logistics.proof_of_delivery p where p.tenant_id=? and p.workspace_id=? " +
                "and p.created_at>=? and p.created_at<?)," +
                "count(*) filter(where status='DELIVERED' and delivery_window_end is not null and updated_at<=delivery_window_end) " +
                "from logistics.dispatch_order d where d.tenant_id=? and d.workspace_id=? " +
                "and d.created_at>=? and d.created_at<?";
        Long[] values = jdbc.queryForObject(sql, (rs, row) -> {
            Long[] result = new Long[6];
            for (int i = 0; i < result.length; i++) result[i] = rs.getLong(i + 1);
            return result;
        }, tenant, workspace, timestamp(from), timestamp(to), tenant, workspace, timestamp(from), timestamp(to),
                tenant, workspace, timestamp(from), timestamp(to));
        double onTime = values[1] == 0 ? 0d : (double) values[5] / values[1];
        return new LogisticsOperationsService.AnalyticsView(from, to, values[0], values[1], values[2], values[3],
                values[4], onTime, averageMinutes(tenant, workspace, "logistics.dispatch.created",
                "logistics.dispatch.preparation-started", from, to), averageMinutes(tenant, workspace,
                "logistics.dispatch.route-started", "logistics.dispatch.delivered", from, to));
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsService.Page<LogisticsOperationsService.ProofOfDeliveryView> proofOfDelivery(
            String tenantId, String workspaceId, String status, int page, int size) {
        pageCheck(page, size);
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        String from = " from logistics.dispatch_order d left join logistics.proof_of_delivery p " +
                "on p.tenant_id=d.tenant_id and p.workspace_id=d.workspace_id and p.dispatch_order_id=d.id";
        String where = " where d.tenant_id=? and d.workspace_id=?";
        if (status != null && !status.isBlank()) {
            String normalized = enumValue(status, "status", new ProofOfDeliveryStatus[]{
                    ProofOfDeliveryStatus.PENDING, ProofOfDeliveryStatus.COMPLETED});
            if ("PENDING".equals(normalized)) {
                where += " and p.id is null and d.status not in ('DELIVERED','CANCELLED')";
            } else {
                where += " and p.status=?";
                args.add(normalized);
            }
        }
        long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        String sql = "select p.id,d.id,d.dispatch_number,case when p.id is null then 'PENDING' else p.status end," +
                "p.receiver_name,p.completed_at,p.notes,coalesce(p.photo_evidence_declared,false)," +
                "coalesce(p.signature_evidence_declared,false),d.updated_at" + from + where +
                " order by d.updated_at desc,d.id desc limit ? offset ?";
        List<LogisticsOperationsService.ProofOfDeliveryView> items = jdbc.query(sql, (rs, row) ->
                new LogisticsOperationsService.ProofOfDeliveryView(
                        rs.getObject(1) == null ? null : rs.getObject(1).toString(), rs.getObject(2).toString(),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                        rs.getBoolean(8), rs.getBoolean(9), rs.getTimestamp(10).toInstant()), pageArgs.toArray());
        return new LogisticsOperationsService.Page<>(items, page, size, total);
    }

    private double averageMinutes(UUID tenant, UUID workspace, String startEvent, String endEvent,
                                  Instant from, Instant to) {
        String sql = "select coalesce(avg(extract(epoch from (finish.occurred_at-start.occurred_at))/60.0),0) " +
                "from logistics.dispatch_order d " +
                "join logistics.dispatch_event start on start.tenant_id=d.tenant_id and start.workspace_id=d.workspace_id " +
                "and start.dispatch_order_id=d.id and start.event_type=? " +
                "join logistics.dispatch_event finish on finish.tenant_id=d.tenant_id and finish.workspace_id=d.workspace_id " +
                "and finish.dispatch_order_id=d.id and finish.event_type=? " +
                "where d.tenant_id=? and d.workspace_id=? and d.created_at>=? and d.created_at<?";
        return jdbc.query(sql, rs -> rs.next() ? rs.getDouble(1) : 0d, startEvent, endEvent, tenant, workspace,
                timestamp(from), timestamp(to));
    }

    private static String buyerEvent(String type) {
        return switch (type) {
            case "logistics.dispatch.scheduled", "logistics.dispatch.reprogrammed" -> "DELIVERY_SCHEDULED";
            case "logistics.dispatch.route-started" -> "IN_TRANSIT";
            case "logistics.dispatch.delivered", "logistics.pod.completed" -> "DELIVERED";
            case "logistics.dispatch.cancelled" -> "DELIVERY_CANCELLED";
            case "logistics.dispatch.incident-recorded", "logistics.dispatch.buyer-temperature-review" -> "DELIVERY_REVIEW";
            default -> "DELIVERY_UPDATED";
        };
    }
}
