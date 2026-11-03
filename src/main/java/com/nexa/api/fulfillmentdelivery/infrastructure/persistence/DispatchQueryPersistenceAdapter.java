package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchSalesOwnerScope;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchQueryPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.dispatchorder.DispatchStatus;
import com.nexa.api.fulfillmentdelivery.domain.proofofdelivery.ProofOfDeliveryStatus;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
@Profile("!test")
public class DispatchQueryPersistenceAdapter extends DispatchJdbcSupport implements DispatchQueryPersistencePort {
    private final WorkforceDirectory workforce;

    public DispatchQueryPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                           WarehouseLogisticsFulfillmentPort warehouseFulfillment,
                                           SalesOrderFulfillmentQuery salesOrders,
                                           CustomerAccountQuery customerAccounts,
                                           WorkforceDirectory workforce) {
        super(jdbc, changeFeed, warehouseFulfillment, salesOrders, customerAccounts);
        this.workforce = workforce;
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> list(
            String tenantId, String workspaceId, String clientAccountId, String status,
            int page, int size, String sort) {
        return list(tenantId, workspaceId, clientAccountId, status, page, size, sort, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> list(
            String tenantId, String workspaceId, String clientAccountId, String status,
            int page, int size, String sort, Set<UUID> grantedWarehouseIds) {
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
        DispatchWarehouseVisibility.Predicate visibility = clientAccountId == null
                ? DispatchWarehouseVisibility.forDispatches("d", visibleDispatches(tenant, workspace,
                        " from logistics.dispatch_order d", where, args, grantedWarehouseIds))
                : DispatchWarehouseVisibility.unrestricted();
        where += visibility.sql();
        args.addAll(visibility.arguments());
        long total = jdbc.queryForObject("select count(*) from logistics.dispatch_order d" + where,
                Long.class, args.toArray());
        String order = DispatchSort.parse(sort).sql();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<DispatchRow> selected = jdbc.query(
                selectSql() + where + " order by " + order + " limit ? offset ?",
                (rs, row) -> read(rs), pageArgs.toArray());
        List<LogisticsOperationsModels.DispatchView> items = withSalesOrderHeaders(tenant, workspace, selected)
                .stream().map(row -> view(row, clientAccountId != null)).toList();
        return new LogisticsOperationsModels.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.Page<LogisticsOperationsModels.DispatchView> listForSalesOwner(
            String tenantId, String workspaceId, String status, int page, int size, String sort,
            DispatchSalesOwnerScope scope) {
        pageCheck(page, size);
        List<UUID> accountIds = salesOwnerAccountIds(scope);
        Set<UUID> warehouseIds = salesOwnerWarehouseIds(scope);
        if (accountIds.isEmpty() || warehouseIds.isEmpty()) {
            return new LogisticsOperationsModels.Page<>(List.of(), page, size, 0);
        }
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        String where = " where d.tenant_id=? and d.workspace_id=? and d.client_account_id in ("
                + placeholders(accountIds.size()) + ")";
        args.addAll(accountIds);
        if (status != null && !status.isBlank()) {
            where += " and d.status=?";
            args.add(enumValue(status, "status", DispatchStatus.values()));
        }
        DispatchWarehouseVisibility.Predicate visibility = DispatchWarehouseVisibility.forDispatches("d",
                visibleDispatches(tenant, workspace, " from logistics.dispatch_order d", where, args,
                        warehouseIds));
        where += visibility.sql();
        args.addAll(visibility.arguments());
        long total = jdbc.queryForObject("select count(*) from logistics.dispatch_order d" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<DispatchRow> selected = jdbc.query(
                selectSql() + where + " order by " + DispatchSort.parse(sort).sql() + " limit ? offset ?",
                (rs, row) -> read(rs), pageArgs.toArray());
        List<LogisticsOperationsModels.DispatchView> items = withSalesOrderHeaders(tenant, workspace, selected)
                .stream().map(row -> view(row, false)).toList();
        return new LogisticsOperationsModels.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DispatchView detail(String tenantId, String workspaceId,
                                                           String clientAccountId, String dispatchId) {
        return detailViewForWarehouses(tenantId, workspaceId, clientAccountId, dispatchId, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DispatchView detail(String tenantId, String workspaceId,
                                                           String clientAccountId, String dispatchId,
                                                           Set<UUID> grantedWarehouseIds) {
        return detailViewForWarehouses(tenantId, workspaceId, clientAccountId, dispatchId, grantedWarehouseIds);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DispatchView detailForSalesOwner(
            String tenantId, String workspaceId, String dispatchId, DispatchSalesOwnerScope scope) {
        List<UUID> accountIds = salesOwnerAccountIds(scope);
        Set<UUID> warehouseIds = salesOwnerWarehouseIds(scope);
        if (accountIds.isEmpty() || warehouseIds.isEmpty()) throw error("RESOURCE_NOT_FOUND", true);
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        List<Object> args = new ArrayList<>(List.of(tenant, workspace, uuid(dispatchId)));
        args.addAll(accountIds);
        String sql = selectSql() + " where d.tenant_id=? and d.workspace_id=? and d.id=? "
                + "and d.client_account_id in (" + placeholders(accountIds.size()) + ")";
        DispatchRow row = jdbc.query(sql, rs -> rs.next() ? read(rs) : null, args.toArray());
        if (row == null || !warehouseVisible(tenant, workspace, row.reservationId(), row.salesOrderId(),
                warehouseIds)) throw error("RESOURCE_NOT_FOUND", true);
        return view(withSalesOrderHeaders(tenant, workspace, List.of(row)).getFirst(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DispatchDeliveriesView dispatchDeliveries(
            String tenantId, String workspaceId, String dispatchOrderId) {
        return dispatchDeliveries(tenantId, workspaceId, dispatchOrderId, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DispatchDeliveriesView dispatchDeliveries(
            String tenantId, String workspaceId, String dispatchOrderId, Set<UUID> grantedWarehouseIds) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID dispatch = uuid(dispatchOrderId);
        detailViewForWarehouses(tenantId, workspaceId, null, dispatchOrderId, grantedWarehouseIds);

        List<Object> deliveryArgs = new ArrayList<>(List.of(tenant, workspace, dispatch));
        List<LogisticsOperationsModels.DispatchDeliveryStatusView> deliveries = jdbc.query(
                "select delivery.id delivery_id,delivery.fulfillment_id,delivery.status delivery_status,"
                        + "delivery.version delivery_version,delivery.updated_at delivery_updated_at,"
                        + "p.id pod_id,p.delivery_id pod_delivery_id,"
                        + "p.status pod_status,p.receiver_name pod_receiver_name,p.completed_at pod_completed_at,"
                        + "p.sealed_at pod_sealed_at,p.photo_evidence_declared pod_photo_evidence_declared,"
                        + "p.signature_evidence_declared pod_signature_evidence_declared "
                        + "from logistics.delivery delivery "
                        + "join logistics.dispatch_order d on d.tenant_id=delivery.tenant_id "
                        + "and d.workspace_id=delivery.workspace_id and d.id=delivery.dispatch_order_id "
                        + "left join logistics.proof_of_delivery p on p.tenant_id=delivery.tenant_id "
                        + "and p.workspace_id=delivery.workspace_id and p.delivery_id=delivery.id "
                        + "where delivery.tenant_id=? and delivery.workspace_id=? and d.id=?"
                        + " order by delivery.created_at,delivery.id",
                (rs, row) -> {
                    UUID podId = rs.getObject("pod_id", UUID.class);
                    LogisticsOperationsModels.DispatchDeliveryPodView pod = podId == null ? null
                            : new LogisticsOperationsModels.DispatchDeliveryPodView(podId,
                            rs.getObject("pod_delivery_id", UUID.class), rs.getString("pod_status"),
                            rs.getString("pod_receiver_name"),
                            rs.getTimestamp("pod_completed_at") == null ? null
                                    : rs.getTimestamp("pod_completed_at").toInstant(),
                            rs.getTimestamp("pod_sealed_at") == null ? null
                                    : rs.getTimestamp("pod_sealed_at").toInstant(),
                            rs.getBoolean("pod_photo_evidence_declared"),
                            rs.getBoolean("pod_signature_evidence_declared"));
                    return new LogisticsOperationsModels.DispatchDeliveryStatusView(
                            rs.getObject("delivery_id", UUID.class),
                            rs.getObject("fulfillment_id", UUID.class), rs.getString("delivery_status"),
                            rs.getLong("delivery_version"), rs.getTimestamp("delivery_updated_at").toInstant(), pod);
                }, deliveryArgs.toArray());
        return new LogisticsOperationsModels.DispatchDeliveriesView(dispatch, deliveries);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsModels.DispatchEventView> events(String tenantId, String workspaceId,
                                                                      String clientAccountId, String dispatchId) {
        return events(tenantId, workspaceId, clientAccountId, dispatchId, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsModels.DispatchEventView> events(String tenantId, String workspaceId,
                                                                      String clientAccountId, String dispatchId,
                                                                      Set<UUID> grantedWarehouseIds) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        UUID dispatch = uuid(dispatchId);
        detailViewForWarehouses(tenantId, workspaceId, clientAccountId, dispatchId, grantedWarehouseIds);
        String buyerVisibility = clientAccountId == null ? "" : " and e.buyer_visible=true";
        List<Object> args = new ArrayList<>(List.of(tenant, workspace, dispatch));
        if (clientAccountId != null) args.add(uuid(clientAccountId));
        return jdbc.query("select e.id,e.event_type,e.from_status,e.to_status,e.occurred_at,e.buyer_visible " +
                        "from logistics.dispatch_event e join logistics.dispatch_order d " +
                        "on d.tenant_id=e.tenant_id and d.workspace_id=e.workspace_id and d.id=e.dispatch_order_id " +
                        "where e.tenant_id=? and e.workspace_id=? and e.dispatch_order_id=?" +
                        buyerVisibility +
                        (clientAccountId == null ? "" : " and d.client_account_id=?") + " order by e.occurred_at,e.id",
                (rs, row) -> {
                    String type = rs.getString(2);
                    boolean buyer = clientAccountId != null;
                    return new LogisticsOperationsModels.DispatchEventView(rs.getObject(1).toString(),
                            buyer ? buyerEvent(type) : type, buyer ? null : rs.getString(3),
                            buyer ? null : rs.getString(4), rs.getTimestamp(5).toInstant().toString(),
                            rs.getBoolean(6), buyer ? buyerEvent(type) : rs.getString(4));
                }, args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsModels.DispatchEventView> eventsForSalesOwner(
            String tenantId, String workspaceId, String dispatchId, DispatchSalesOwnerScope scope) {
        List<UUID> accountIds = salesOwnerAccountIds(scope);
        if (accountIds.isEmpty() || salesOwnerWarehouseIds(scope).isEmpty()) {
            throw error("RESOURCE_NOT_FOUND", true);
        }
        detailForSalesOwner(tenantId, workspaceId, dispatchId, scope);
        List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId), uuid(dispatchId)));
        args.addAll(accountIds);
        return jdbc.query("select e.id,e.event_type,e.from_status,e.to_status,e.occurred_at,e.buyer_visible "
                        + "from logistics.dispatch_event e join logistics.dispatch_order d "
                        + "on d.tenant_id=e.tenant_id and d.workspace_id=e.workspace_id and d.id=e.dispatch_order_id "
                        + "where e.tenant_id=? and e.workspace_id=? and e.dispatch_order_id=? "
                        + "and d.client_account_id in (" + placeholders(accountIds.size()) + ") "
                        + "order by e.occurred_at,e.id",
                (rs, row) -> new LogisticsOperationsModels.DispatchEventView(rs.getObject(1).toString(),
                        rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant().toString(), rs.getBoolean(6), rs.getString(4)),
                args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DashboardView dashboard(String tenantId, String workspaceId) {
        return dashboard(tenantId, workspaceId, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.DashboardView dashboard(String tenantId, String workspaceId,
                                                               Set<UUID> grantedWarehouseIds) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        Set<UUID> warehouseIds = normalizedWarehouseIds(grantedWarehouseIds);
        DispatchWarehouseVisibility.Predicate visibility = DispatchWarehouseVisibility.forDispatches("d",
                visibleDispatchesForAll(tenant, workspace, warehouseIds));
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        args.addAll(visibility.arguments());
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
                        "select 1 from logistics.proof_of_delivery p where p.tenant_id=d.tenant_id " +
                        "and p.workspace_id=d.workspace_id and p.dispatch_order_id=d.id))," +
                        "0::bigint from logistics.dispatch_order d where d.tenant_id=? and d.workspace_id=?" + visibility.sql(),
                (rs, row) -> {
                    Long[] result = new Long[11];
                    for (int i = 0; i < result.length; i++) result[i] = rs.getLong(i + 1);
                    return result;
                }, args.toArray());
        long reservations = warehouseFulfillment.countReadyReservations(tenantId, workspaceId, warehouseIds,
                Instant.now());
        return new LogisticsOperationsModels.DashboardView(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9], reservations);
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.AnalyticsView analytics(String tenantId, String workspaceId,
                                                               Instant from, Instant to) {
        return analytics(tenantId, workspaceId, from, to, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.AnalyticsView analytics(String tenantId, String workspaceId,
                                                               Instant from, Instant to,
                                                               Set<UUID> grantedWarehouseIds) {
        UUID tenant = uuid(tenantId);
        UUID workspace = uuid(workspaceId);
        Set<UUID> warehouseIds = normalizedWarehouseIds(grantedWarehouseIds);
        List<Object> candidateArgs = new ArrayList<>(List.of(tenant, workspace, timestamp(from), timestamp(to)));
        DispatchWarehouseVisibility.Predicate visibility = DispatchWarehouseVisibility.forDispatches("d",
                visibleDispatches(tenant, workspace, " from logistics.dispatch_order d",
                        " where d.tenant_id=? and d.workspace_id=? and d.created_at>=? and d.created_at<?",
                        candidateArgs, warehouseIds));
        String sql = "with visible_dispatch as (select d.* from logistics.dispatch_order d " +
                "where d.tenant_id=? and d.workspace_id=?" + visibility.sql() + ") " +
                "select count(*),count(*) filter(where status='DELIVERED')," +
                "count(*) filter(where status='INCIDENT')," +
                "(select count(*) from logistics.temperature_reading t join visible_dispatch vd " +
                "on vd.tenant_id=t.tenant_id and vd.workspace_id=t.workspace_id and vd.id=t.dispatch_order_id " +
                "where t.tenant_id=? and t.workspace_id=? and t.recorded_at>=? and t.recorded_at<? " +
                "and t.status='OUT_OF_RANGE')," +
                "(select count(*) from logistics.proof_of_delivery p join visible_dispatch vd " +
                "on vd.tenant_id=p.tenant_id and vd.workspace_id=p.workspace_id and vd.id=p.dispatch_order_id " +
                "where p.tenant_id=? and p.workspace_id=? and p.created_at>=? and p.created_at<?)," +
                "count(*) filter(where status='DELIVERED' and delivery_window_end is not null and updated_at<=delivery_window_end) " +
                "from visible_dispatch d where d.created_at>=? and d.created_at<?";
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        args.addAll(visibility.arguments());
        args.addAll(List.of(tenant, workspace, timestamp(from), timestamp(to),
                tenant, workspace, timestamp(from), timestamp(to), timestamp(from), timestamp(to)));
        Long[] values = jdbc.queryForObject(sql, (rs, row) -> {
            Long[] result = new Long[6];
            for (int i = 0; i < result.length; i++) result[i] = rs.getLong(i + 1);
            return result;
        }, args.toArray());
        double onTime = values[1] == 0 ? 0d : (double) values[5] / values[1];
        return new LogisticsOperationsModels.AnalyticsView(from, to, values[0], values[1], values[2], values[3],
                values[4], onTime, averageMinutes(tenant, workspace, "logistics.dispatch.created",
                "logistics.dispatch.preparation-started", from, to, visibility), averageMinutes(tenant,
                workspace, "logistics.dispatch.route-started", "logistics.dispatch.delivered", from, to,
                visibility));
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.Page<LogisticsOperationsModels.ProofOfDeliveryView> proofOfDelivery(
            String tenantId, String workspaceId, String status, int page, int size) {
        return proofOfDelivery(tenantId, workspaceId, status, page, size, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public LogisticsOperationsModels.Page<LogisticsOperationsModels.ProofOfDeliveryView> proofOfDelivery(
            String tenantId, String workspaceId, String status, int page, int size,
            Set<UUID> grantedWarehouseIds) {
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
                where += " and p.id is null and d.status not in ('DELIVERED','PARTIAL','CANCELLED')";
            } else {
                where += " and p.status=?";
                args.add(normalized);
            }
        }
        DispatchWarehouseVisibility.Predicate warehouseVisibility = DispatchWarehouseVisibility.forDispatches("d",
                visibleDispatches(tenant, workspace, from, where, args,
                        normalizedWarehouseIds(grantedWarehouseIds)));
        where += warehouseVisibility.sql();
        args.addAll(warehouseVisibility.arguments());
        long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        String sql = "select p.id,d.id,d.dispatch_number,case when p.id is null then 'PENDING' else p.status end," +
                "p.receiver_name,p.completed_at,p.notes,coalesce(p.photo_evidence_declared,false)," +
                "coalesce(p.signature_evidence_declared,false),d.updated_at" + from + where +
                " order by d.updated_at desc,d.id desc limit ? offset ?";
        List<LogisticsOperationsModels.ProofOfDeliveryView> items = jdbc.query(sql, (rs, row) ->
                new LogisticsOperationsModels.ProofOfDeliveryView(
                        rs.getObject(1) == null ? null : rs.getObject(1).toString(), rs.getObject(2).toString(),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7),
                        rs.getBoolean(8), rs.getBoolean(9), rs.getTimestamp(10).toInstant()), pageArgs.toArray());
        return new LogisticsOperationsModels.Page<>(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LogisticsOperationsModels.AssigneeView> assignees(String tenantId, String workspaceId) {
        return workforce.findLogisticsAssignees(uuid(tenantId), uuid(workspaceId)).stream()
                .map(value -> new LogisticsOperationsModels.AssigneeView(value.id().toString(), value.email(),
                        value.displayName()))
                .toList();
    }

    private double averageMinutes(UUID tenant, UUID workspace, String startEvent, String endEvent,
                                  Instant from, Instant to, DispatchWarehouseVisibility.Predicate visibility) {
        String sql = "select coalesce(avg(extract(epoch from (finish.occurred_at-start.occurred_at))/60.0),0) " +
                "from logistics.dispatch_order d " +
                "join logistics.dispatch_event start on start.tenant_id=d.tenant_id and start.workspace_id=d.workspace_id " +
                "and start.dispatch_order_id=d.id and start.event_type=? " +
                "join logistics.dispatch_event finish on finish.tenant_id=d.tenant_id and finish.workspace_id=d.workspace_id " +
                "and finish.dispatch_order_id=d.id and finish.event_type=? " +
                "where d.tenant_id=? and d.workspace_id=?" + visibility.sql() +
                " and d.created_at>=? and d.created_at<?";
        List<Object> args = new ArrayList<>(List.of(startEvent, endEvent, tenant, workspace));
        args.addAll(visibility.arguments());
        args.addAll(List.of(timestamp(from), timestamp(to)));
        return jdbc.query(sql, rs -> rs.next() ? rs.getDouble(1) : 0d, args.toArray());
    }

    private Set<UUID> visibleDispatchesForAll(UUID tenant, UUID workspace, Set<UUID> grantedWarehouseIds) {
        List<Object> args = new ArrayList<>(List.of(tenant, workspace));
        return visibleDispatches(tenant, workspace, " from logistics.dispatch_order d",
                " where d.tenant_id=? and d.workspace_id=?", args, grantedWarehouseIds);
    }

    private Set<UUID> visibleDispatches(UUID tenant, UUID workspace, String from, String where,
                                        List<Object> arguments, Set<UUID> grantedWarehouseIds) {
        if (grantedWarehouseIds == null || grantedWarehouseIds.isEmpty()) return Set.of();
        List<DispatchVisibilityCandidate> candidates = jdbc.query(
                "select distinct d.id,d.inventory_reservation_id,d.sales_order_id" + from + where,
                (rs, row) -> new DispatchVisibilityCandidate(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)), arguments.toArray());
        if (candidates.isEmpty()) return Set.of();
        Set<WarehouseLogisticsFulfillmentPort.ReservationOrderReference> references = candidates.stream()
                .filter(candidate -> candidate.reservationId() != null && candidate.salesOrderId() != null)
                .map(candidate -> new WarehouseLogisticsFulfillmentPort.ReservationOrderReference(
                        candidate.reservationId(), candidate.salesOrderId()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (references.isEmpty()) return Set.of();
        Set<WarehouseLogisticsFulfillmentPort.ReservationOrderReference> visibleReferences =
                warehouseFulfillment.visibleReservationReferences(tenant.toString(), workspace.toString(),
                        references, grantedWarehouseIds);
        return candidates.stream().filter(candidate -> candidate.reservationId() != null
                        && candidate.salesOrderId() != null)
                .filter(candidate -> visibleReferences.contains(
                        new WarehouseLogisticsFulfillmentPort.ReservationOrderReference(
                                candidate.reservationId(), candidate.salesOrderId())))
                .map(DispatchVisibilityCandidate::dispatchId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record DispatchVisibilityCandidate(UUID dispatchId, UUID reservationId, UUID salesOrderId) { }

    private static List<UUID> salesOwnerAccountIds(DispatchSalesOwnerScope scope) {
        if (scope == null || scope.activeClientAccountIds() == null || scope.activeClientAccountIds().isEmpty()) {
            return List.of();
        }
        return scope.activeClientAccountIds().stream().sorted().toList();
    }

    private static Set<UUID> salesOwnerWarehouseIds(DispatchSalesOwnerScope scope) {
        return scope == null ? Set.of() : normalizedWarehouseIds(scope.activeWarehouseIds());
    }

    private static Set<UUID> normalizedWarehouseIds(Set<UUID> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) return Set.of();
        return warehouseIds.stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String buyerEvent(String type) {
        return switch (type) {
            case "logistics.dispatch.scheduled", "logistics.dispatch.reprogrammed" -> "DELIVERY_SCHEDULED";
            case "logistics.dispatch.route-started" -> "IN_TRANSIT";
            case "logistics.dispatch.delivered", "logistics.pod.completed" -> "DELIVERED";
            case "logistics.dispatch.cancelled" -> "DELIVERY_CANCELLED";
            case "logistics.dispatch.incident-recorded", "logistics.dispatch.buyer-temperature-review" -> "DELIVERY_REVIEW";
            case "logistics.delivery.attempt-failed" -> "DELIVERY_REVIEW";
            case "logistics.delivery.partially-completed" -> "PARTIAL";
            case "logistics.delivery.continuation-created" -> "CONTINUATION_REQUIRED";
            default -> "DELIVERY_UPDATED";
        };
    }
}
