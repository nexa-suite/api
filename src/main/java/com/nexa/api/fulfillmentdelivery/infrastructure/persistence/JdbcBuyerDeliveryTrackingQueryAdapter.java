package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.DeliveryRecord;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.EventView;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.Page;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingQueryPort;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BC-06-owned delivery facts joined only to the Sales Order owner's scoped references. */
@Repository
@Profile("!test")
public class JdbcBuyerDeliveryTrackingQueryAdapter implements BuyerDeliveryTrackingQueryPort {
    private static final int SALES_ORDER_BATCH_SIZE = 250;

    private final JdbcTemplate jdbc;
    private final SalesOrderFulfillmentQuery salesOrders;

    public JdbcBuyerDeliveryTrackingQueryAdapter(JdbcTemplate jdbc, SalesOrderFulfillmentQuery salesOrders) {
        this.jdbc = jdbc;
        this.salesOrders = salesOrders;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryRecord> list(UUID tenantId, UUID workspaceId, UUID clientAccountId, int page, int size) {
        if (tenantId == null || workspaceId == null || clientAccountId == null
                || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("A scoped Buyer delivery page is required");
        }
        long start = Math.multiplyExact((long) page, size);
        long end = start + size;
        long total = 0;
        int salesOrderPage = 0;
        List<DeliveryRecord> selected = new ArrayList<>(size);

        while (true) {
            SalesOrderFulfillmentQuery.OrderReferencePage orders = salesOrders.findReferencesByClientAccount(
                    tenantId, workspaceId, clientAccountId, salesOrderPage, SALES_ORDER_BATCH_SIZE);
            if (orders.items().isEmpty()) break;
            Map<UUID, List<DeliveryRecord>> bySalesOrder = queryDeliveries(
                    tenantId, workspaceId, orders.items().stream().map(SalesOrderFulfillmentQuery.OrderReference::id).toList());
            for (SalesOrderFulfillmentQuery.OrderReference order : orders.items()) {
                for (DeliveryRecord record : bySalesOrder.getOrDefault(order.id(), List.of())) {
                    if (total >= start && total < end) selected.add(withOrderNumber(record, order.number()));
                    total++;
                }
            }
            if (!orders.hasMore()) break;
            salesOrderPage++;
        }
        return new Page<>(selected, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryRecord detail(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        if (tenantId == null || workspaceId == null || deliveryId == null) return null;
        return jdbc.query("select d.id,f.sales_order_id,d.status,d.destination_snapshot,d.scheduled_at,"
                        + "d.dispatched_at,d.delivered_at,p.status as proof_status,d.version,d.created_at,d.updated_at "
                        + "from logistics.delivery d join logistics.fulfillment f "
                        + "on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join logistics.proof_of_delivery p on p.tenant_id=d.tenant_id "
                        + "and p.workspace_id=d.workspace_id and p.delivery_id=d.id "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? and d.fulfillment_id is not null",
                (rs, row) -> read(rs, null), tenantId, workspaceId, deliveryId).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventView> events(UUID tenantId, UUID workspaceId, UUID deliveryId) {
        if (tenantId == null || workspaceId == null || deliveryId == null) return List.of();
        UUID fulfillmentId = jdbc.query("select fulfillment_id from logistics.delivery "
                        + "where tenant_id=? and workspace_id=? and id=? and fulfillment_id is not null",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, deliveryId)
                .stream().findFirst().orElse(null);
        if (fulfillmentId == null) return List.of();

        List<EventView> events = new ArrayList<>();
        events.addAll(jdbc.query("select 'HANDED_OVER' as event_type,occurred_at "
                        + "from logistics.fulfillment_event where tenant_id=? and workspace_id=? "
                        + "and fulfillment_id=? and event_type='HAND_OVER' and to_status='HANDED_OVER'",
                (rs, row) -> event(rs.getString("event_type"), rs.getTimestamp("occurred_at")),
                tenantId, workspaceId, fulfillmentId));
        events.addAll(jdbc.query("select 'IN_TRANSIT' as event_type,occurred_at "
                        + "from logistics.delivery_event where tenant_id=? and workspace_id=? and delivery_id=? "
                        + "and event_type='TRANSIT_START'",
                (rs, row) -> event(rs.getString("event_type"), rs.getTimestamp("occurred_at")),
                tenantId, workspaceId, deliveryId));
        events.addAll(jdbc.query("select case coalesce(outcome,status) "
                        + "when 'PENDING' then 'ATTEMPT_STARTED' when 'DELIVERED' then 'DELIVERED' "
                        + "when 'FINAL' then 'DELIVERED' when 'PARTIAL' then 'PARTIAL' "
                        + "when 'FAILED' then 'ATTEMPT_FAILED' when 'REJECTED' then 'ATTEMPT_REJECTED' "
                        + "when 'REFUSED' then 'ATTEMPT_REFUSED' when 'ABSENT' then 'ATTEMPT_ABSENT' "
                        + "when 'UNDELIVERED' then 'ATTEMPT_UNDELIVERED' else null end as event_type, "
                        + "coalesce(attempted_at,occurred_at) as occurred_at from logistics.delivery_attempt "
                        + "where tenant_id=? and workspace_id=? and delivery_id=? "
                        + "and coalesce(attempted_at,occurred_at) is not null "
                        + "and coalesce(outcome,status) in ('PENDING','DELIVERED','FINAL','PARTIAL','FAILED',"
                        + "'REJECTED','REFUSED','ABSENT','UNDELIVERED')",
                (rs, row) -> event(rs.getString("event_type"), rs.getTimestamp("occurred_at")),
                tenantId, workspaceId, deliveryId));
        events.addAll(jdbc.query("select 'PROOF_OF_DELIVERY_AVAILABLE' as event_type,sealed_at as occurred_at "
                        + "from logistics.proof_of_delivery where tenant_id=? and workspace_id=? and delivery_id=? "
                        + "and status='SEALED' and sealed_at is not null",
                (rs, row) -> event(rs.getString("event_type"), rs.getTimestamp("occurred_at")),
                tenantId, workspaceId, deliveryId));
        events.sort(Comparator.comparing(EventView::occurredAt).thenComparing(EventView::type));
        return List.copyOf(events);
    }

    private Map<UUID, List<DeliveryRecord>> queryDeliveries(UUID tenantId, UUID workspaceId,
                                                            List<UUID> salesOrderIds) {
        if (salesOrderIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(salesOrderIds.size(), "?"));
        List<Object> arguments = new ArrayList<>(salesOrderIds.size() + 2);
        arguments.add(tenantId);
        arguments.add(workspaceId);
        arguments.addAll(salesOrderIds);
        Map<UUID, List<DeliveryRecord>> records = new LinkedHashMap<>();
        jdbc.query("select d.id,f.sales_order_id,d.status,d.destination_snapshot,d.scheduled_at,d.dispatched_at,"
                        + "d.delivered_at,p.status as proof_status,d.version,d.created_at,d.updated_at "
                        + "from logistics.delivery d join logistics.fulfillment f "
                        + "on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join logistics.proof_of_delivery p on p.tenant_id=d.tenant_id "
                        + "and p.workspace_id=d.workspace_id and p.delivery_id=d.id "
                        + "where d.tenant_id=? and d.workspace_id=? and d.fulfillment_id is not null "
                        + "and f.sales_order_id in (" + placeholders + ") "
                        + "order by f.sales_order_id,d.created_at desc,d.id asc",
                rs -> {
                    DeliveryRecord row = read(rs, null);
                    records.computeIfAbsent(row.salesOrderId(), ignored -> new ArrayList<>()).add(row);
                }, arguments.toArray());
        return records;
    }

    private static DeliveryRecord read(java.sql.ResultSet rs, String salesOrderNumber) throws java.sql.SQLException {
        return new DeliveryRecord(rs.getObject("id", UUID.class), rs.getObject("sales_order_id", UUID.class),
                salesOrderNumber, rs.getString("status"), rs.getString("destination_snapshot"),
                instant(rs, "scheduled_at"), instant(rs, "dispatched_at"), instant(rs, "delivered_at"),
                rs.getString("proof_status"), rs.getLong("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static DeliveryRecord withOrderNumber(DeliveryRecord record, String orderNumber) {
        return new DeliveryRecord(record.id(), record.salesOrderId(), orderNumber, record.status(),
                record.destination(), record.scheduledAt(), record.dispatchedAt(), record.deliveredAt(),
                record.proofOfDeliveryStatus(), record.version(), record.createdAt(), record.updatedAt());
    }

    private static EventView event(String type, Timestamp occurredAt) {
        return new EventView(type, occurredAt.toInstant());
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
