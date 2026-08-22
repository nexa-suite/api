package com.nexa.api.logistics.infrastructure.persistence;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.LogisticsOperationsService.LogisticsException;
import com.nexa.api.logistics.application.port.OperationalHandoffNotificationPort;
import com.nexa.api.logistics.domain.dispatchorder.ClientAccountId;
import com.nexa.api.logistics.domain.dispatchorder.DeliveryWindow;
import com.nexa.api.logistics.domain.dispatchorder.DestinationSnapshot;
import com.nexa.api.logistics.domain.dispatchorder.DispatchNumber;
import com.nexa.api.logistics.domain.dispatchorder.DispatchOrder;
import com.nexa.api.logistics.domain.dispatchorder.DispatchStatus;
import com.nexa.api.logistics.domain.dispatchorder.InventoryReservationId;
import com.nexa.api.logistics.domain.dispatchorder.SalesOrderId;
import com.nexa.api.logistics.domain.dispatchorder.TransportAssignment;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Low-level tenant-scoped JDBC primitives for Logistics adapters.
 *
 * This class deliberately contains no dispatch workflow. Command, query,
 * route-start and handoff adapters own their respective use cases.
 */
abstract class DispatchJdbcSupport {
    static final int MAX_PAGE_SIZE = 100;

    protected final JdbcTemplate jdbc;
    protected final ChangeEventPersistencePort changeFeed;
    protected final WarehouseLogisticsFulfillmentPort warehouseFulfillment;
    protected final OperationalHandoffNotificationPort handoffNotifications;

    protected DispatchJdbcSupport(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                  WarehouseLogisticsFulfillmentPort warehouseFulfillment,
                                  OperationalHandoffNotificationPort handoffNotifications) {
        this.jdbc = jdbc;
        this.changeFeed = changeFeed;
        this.warehouseFulfillment = warehouseFulfillment;
        this.handoffNotifications = handoffNotifications;
    }

    protected DispatchJdbcSupport(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed,
                                  WarehouseLogisticsFulfillmentPort warehouseFulfillment) {
        this(jdbc, changeFeed, warehouseFulfillment, notification -> { });
    }

    protected DispatchRow locked(UUID tenant, UUID workspace, UUID id, UUID client) {
        return load(tenant, workspace, id, client, true);
    }

    protected DispatchRow load(UUID tenant, UUID workspace, UUID id, UUID client, boolean lock) {
        String sql = selectSql() + " where d.tenant_id=? and d.workspace_id=? and d.id=?";
        List<Object> args = new ArrayList<>(List.of(tenant, workspace, id));
        if (client != null) {
            sql += " and d.client_account_id=?";
            args.add(client);
        }
        if (lock) sql += " for update of d";
        return jdbc.query(sql, rs -> rs.next() ? read(rs) : null, args.toArray());
    }

    protected LogisticsOperationsService.DispatchView detailView(String tenantId, String workspaceId,
                                                                  String clientAccountId, String dispatchId) {
        DispatchRow row = load(uuid(tenantId), uuid(workspaceId), uuid(dispatchId),
                clientAccountId == null ? null : uuid(clientAccountId), false);
        if (row == null) throw error("RESOURCE_NOT_FOUND", true);
        return view(row, clientAccountId != null);
    }

    protected String selectSql() {
        return "select d.id,d.dispatch_number,d.inventory_reservation_id,d.sales_order_id,d.client_account_id," +
                "d.status,d.destination_snapshot,d.client_code_snapshot,d.client_name_snapshot," +
                "d.delivery_area_snapshot,d.priority,d.delivery_window_start,d.delivery_window_end,d.eta," +
                "d.responsible_membership_id,d.responsible_display_name_snapshot,d.vehicle_reference,d.route_name," +
                "d.temperature_min,d.temperature_max,d.temperature_unit,d.temperature_status,d.version,d.updated_at," +
                "p.id,p.status,o.number,a.id,a.attempt_number,a.status,a.failure_reason,a.occurred_at,c.id,c.status," +
                "d.tenant_id,d.workspace_id from logistics.dispatch_order d " +
                "join sales.sales_order o on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.sales_order_id " +
                "left join logistics.proof_of_delivery p on p.tenant_id=d.tenant_id and p.workspace_id=d.workspace_id " +
                "and p.dispatch_order_id=d.id " +
                "left join lateral (select da.id,da.attempt_number,da.status,da.failure_reason,da.occurred_at " +
                "from logistics.delivery_attempt da where da.tenant_id=d.tenant_id and da.workspace_id=d.workspace_id " +
                "and da.delivery_id=d.id order by da.attempt_number desc,da.occurred_at desc,da.id desc limit 1) a on true " +
                "left join lateral (select cd.id,cd.status from logistics.continuation_delivery cd " +
                "where cd.tenant_id=d.tenant_id and cd.workspace_id=d.workspace_id and cd.source_delivery_id=d.id " +
                "order by cd.created_at desc,cd.id desc limit 1) c on true";
    }

    protected DispatchRow read(ResultSet rs) throws java.sql.SQLException {
        return new DispatchRow(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                instant(rs, 12), instant(rs, 13), instant(rs, 14), rs.getObject(15, UUID.class),
                rs.getString(16), rs.getString(17), rs.getString(18), rs.getBigDecimal(19),
                rs.getBigDecimal(20), rs.getString(21), rs.getString(22), rs.getLong(23), instant(rs, 24),
                rs.getObject(25, UUID.class) == null ? null : rs.getObject(25, UUID.class).toString(),
                rs.getString(26), rs.getString(27),
                rs.getObject(28, UUID.class) == null ? null : rs.getObject(28, UUID.class).toString(),
                rs.getInt(29), rs.getString(30), rs.getString(31), instant(rs, 32),
                rs.getObject(33, UUID.class) == null ? null : rs.getObject(33, UUID.class).toString(),
                rs.getString(34), rs.getObject(35, UUID.class), rs.getObject(36, UUID.class));
    }

    protected LogisticsOperationsService.DispatchView view(DispatchRow row, boolean buyer) {
        LogisticsOperationsService.AssignmentView assignment = row.responsibleMembershipId() == null ? null :
                new LogisticsOperationsService.AssignmentView(
                        buyer ? null : row.responsibleMembershipId().toString(),
                        buyer ? null : row.responsibleDisplayName(),
                        buyer ? null : row.vehicleReference(),
                        buyer ? null : row.routeName());
        List<String> alerts = new ArrayList<>();
        if ("OUT_OF_RANGE".equals(row.temperatureStatus())) alerts.add("TEMPERATURE_ALERT");
        if ("IN_ROUTE".equals(row.status()) && row.podStatus() == null) alerts.add("POD_PENDING");
        if (row.continuationDeliveryId() != null) alerts.add("CONTINUATION_REQUIRED");
        LogisticsOperationsService.DeliveryAttemptView lastAttempt = row.lastAttemptId() == null ? null :
                new LogisticsOperationsService.DeliveryAttemptView(row.lastAttemptId(), row.lastAttemptNumber(),
                        row.lastAttemptStatus(), row.lastAttemptFailureReason(), row.lastAttemptOccurredAt(),
                        attemptLines(row));
        LogisticsOperationsService.DispatchView value = new LogisticsOperationsService.DispatchView(
                row.id().toString(), row.dispatchNumber(), row.reservationId().toString(), row.salesOrderId().toString(),
                row.salesOrderNumber(), row.clientAccountId().toString(), row.clientCode(), row.clientName(), row.status(),
                row.destination(), row.deliveryArea(), row.priority(), row.windowStart(), row.windowEnd(), row.eta(),
                assignment, row.temperatureMin(), row.temperatureMax(), row.temperatureUnit(), row.temperatureStatus(),
                row.podId(), row.podStatus(), row.version(), row.updatedAt(), alerts, lastAttempt,
                row.continuationDeliveryId(), row.continuationDeliveryStatus(), continuationLines(row));
        return buyer ? value.buyerSafe() : value;
    }

    private List<LogisticsOperationsService.ObligationLineView> attemptLines(DispatchRow row) {
        if (row.lastAttemptId() == null) return List.of();
        return jdbc.query("select catalog_item_id,quantity,unit from logistics.delivery_attempt_line " +
                        "where tenant_id=? and workspace_id=? and delivery_attempt_id=? order by catalog_item_id",
                (rs, index) -> new LogisticsOperationsService.ObligationLineView(
                        rs.getString(1), rs.getBigDecimal(2), rs.getString(3)),
                row.tenantId(), row.workspaceId(), UUID.fromString(row.lastAttemptId()));
    }

    private List<LogisticsOperationsService.ObligationLineView> continuationLines(DispatchRow row) {
        if (row.continuationDeliveryId() == null) return List.of();
        return jdbc.query("select catalog_item_id,quantity,unit from logistics.continuation_delivery_line " +
                        "where tenant_id=? and workspace_id=? and continuation_delivery_id=? order by catalog_item_id",
                (rs, index) -> new LogisticsOperationsService.ObligationLineView(
                        rs.getString(1), rs.getBigDecimal(2), rs.getString(3)),
                row.tenantId(), row.workspaceId(), UUID.fromString(row.continuationDeliveryId()));
    }

    protected DispatchOrder aggregate(DispatchRow row) {
        return DispatchOrder.rehydrate(row.id(), new DispatchNumber(row.dispatchNumber()),
                new InventoryReservationId(row.reservationId()), new SalesOrderId(row.salesOrderId()),
                new ClientAccountId(row.clientAccountId()), new DestinationSnapshot(row.destination()),
                DispatchStatus.valueOf(row.status()), row.responsibleMembershipId() == null ? null :
                        new TransportAssignment(row.responsibleMembershipId(), row.responsibleDisplayName(),
                                row.vehicleReference(), row.routeName()),
                row.windowStart() == null ? null : new DeliveryWindow(row.windowStart(), row.windowEnd()),
                row.eta(), row.version());
    }

    protected void appendEvent(UUID tenant, UUID workspace, UUID id, String eventType, String from, String to,
                               UUID actor, boolean buyerVisible, String reason, long now, UUID client) {
        jdbc.update("insert into logistics.dispatch_event(id,tenant_id,workspace_id,dispatch_order_id,event_type," +
                        "from_status,to_status,actor_membership_id,buyer_visible,reason,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, workspace, id, eventType, from, to, actor, buyerVisible, reason, timestamp(now));
        String publicStatus = to == null ? "ACTIVE" : to;
        changeFeed.append(tenant.toString(), workspace.toString(), client == null ? null : client.toString(),
                "dispatch_order", id.toString(), eventType, publicStatus, now, buyerVisible);
    }

    protected LogisticsOperationsService.DispatchView replay(UUID tenant, UUID workspace, String operation,
                                                              String key, String hash) {
        lockIdempotency(tenant, workspace, operation, key);
        Idem value = jdbc.query("select response_json,request_hash from logistics.command_idempotency " +
                        "where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",
                rs -> rs.next() ? new Idem(rs.getString(1), rs.getString(2)) : null,
                tenant, workspace, operation, key);
        if (value == null) return null;
        if (!value.hash().equalsIgnoreCase(hash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
        return detailView(tenant.toString(), workspace.toString(), null, value.resource());
    }

    protected LogisticsOperationsService.HandoffNoteView replayHandoff(UUID tenant, UUID workspace,
                                                                        String key, String hash) {
        lockIdempotency(tenant, workspace, "dispatch-handoff-note", key);
        Idem value = jdbc.query("select response_json,request_hash from logistics.command_idempotency " +
                        "where tenant_id=? and workspace_id=? and operation='dispatch-handoff-note' and idempotency_key=?",
                rs -> rs.next() ? new Idem(rs.getString(1), rs.getString(2)) : null, tenant, workspace, key);
        if (value == null) return null;
        if (!value.hash().equalsIgnoreCase(hash)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
        return handoffNoteById(tenant, workspace, uuid(value.resource()));
    }

    protected LogisticsOperationsService.HandoffNoteView handoffNoteById(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select e.id,e.dispatch_order_id,e.reason,e.actor_membership_id,e.occurred_at,d.version " +
                        "from logistics.dispatch_event e join logistics.dispatch_order d " +
                        "on d.tenant_id=e.tenant_id and d.workspace_id=e.workspace_id and d.id=e.dispatch_order_id " +
                        "where e.tenant_id=? and e.workspace_id=? and e.id=? " +
                        "and e.event_type='warehouse.logistics.handoff-note'",
                (rs, row) -> new LogisticsOperationsService.HandoffNoteView(rs.getObject("id").toString(),
                        rs.getObject("dispatch_order_id").toString(), rs.getString("reason"),
                        rs.getObject("actor_membership_id").toString(), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getLong("version")), tenant, workspace, id).stream().findFirst()
                .orElseThrow(() -> error("RESOURCE_NOT_FOUND", true));
    }

    protected static LogisticsOperationsService.HandoffNoteView valueView(
            com.nexa.api.logistics.domain.handoff.OperationalHandoffNote value) {
        return new LogisticsOperationsService.HandoffNoteView(value.id().toString(), value.dispatchOrderId().toString(),
                value.note(), value.authorMembershipId().toString(), value.occurredAt(), value.dispatchVersion());
    }

    protected void lockIdempotency(UUID tenant, UUID workspace, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|" + operation + "|" + key);
    }

    protected void saveIdempotency(UUID tenant, UUID workspace, String operation, String key, String hash,
                                   UUID resource, long now) {
        if (jdbc.update("insert into logistics.command_idempotency(tenant_id,workspace_id,operation,idempotency_key," +
                        "request_hash,response_json,created_at) values (?,?,?,?,?,?,?) " +
                        "on conflict (tenant_id,workspace_id,operation,idempotency_key) do nothing",
                tenant, workspace, operation, key, hash, resource.toString(), timestamp(now)) != 1) {
            Idem prior = jdbc.query("select response_json,request_hash from logistics.command_idempotency " +
                            "where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",
                    rs -> rs.next() ? new Idem(rs.getString(1), rs.getString(2)) : null,
                    tenant, workspace, operation, key);
            if (prior == null || !prior.hash().equalsIgnoreCase(hash)) {
                throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            }
        }
    }

    protected BusinessCard businessCard(UUID tenant, UUID workspace, UUID salesOrderId, String destination) {
        return jdbc.query("select c.code,coalesce(nullif(c.commercial_name,''),c.business_name),o.priority " +
                        "from sales.sales_order o join sales.client_account c on c.tenant_id=o.tenant_id " +
                        "and c.workspace_id=o.workspace_id and c.id=o.client_account_id " +
                        "where o.tenant_id=? and o.workspace_id=? and o.id=?",
                rs -> rs.next() ? new BusinessCard(rs.getString(1), rs.getString(2), rs.getString(3), destination) :
                        new BusinessCard(null, null, "NORMAL", destination), tenant, workspace, salesOrderId);
    }

    protected long nextDispatchNumber(UUID tenant, UUID workspace, int year) {
        jdbc.update("insert into logistics.dispatch_number_counter(tenant_id,workspace_id,dispatch_year,next_value) " +
                        "values (?,?,?,1) on conflict (tenant_id,workspace_id,dispatch_year) do nothing", tenant, workspace, year);
        Long value = jdbc.queryForObject("select next_value from logistics.dispatch_number_counter " +
                        "where tenant_id=? and workspace_id=? and dispatch_year=? for update", Long.class,
                tenant, workspace, year);
        jdbc.update("update logistics.dispatch_number_counter set next_value=? " +
                        "where tenant_id=? and workspace_id=? and dispatch_year=?", value + 1, tenant, workspace, year);
        return value;
    }

    protected static void pageCheck(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw error("INVALID_REQUEST", false);
    }

    protected static String sort(String value, String... pairs) {
        String[] parts = value == null || value.isBlank() ? new String[]{"updatedAt"} : value.split(",", -1);
        String key = parts[0];
        if (parts.length > 2 || (parts.length == 2 && !"asc".equalsIgnoreCase(parts[1]) && !"desc".equalsIgnoreCase(parts[1]))) {
            throw error("INVALID_INVENTORY_SORT", false);
        }
        String direction = parts.length == 2 ? " " + parts[1].toLowerCase(Locale.ROOT) : "";
        for (int i = 0; i < pairs.length; i += 2) {
            if (!pairs[i].equals(key)) continue;
            if (direction.isBlank()) return pairs[i + 1];
            return java.util.Arrays.stream(pairs[i + 1].split(","))
                    .map(term -> {
                        String normalized = term.trim().replace(" asc", "").replace(" desc", "");
                        int nulls = normalized.indexOf(" nulls ");
                        return nulls < 0 ? normalized + direction : normalized.substring(0, nulls) + direction + normalized.substring(nulls);
                    }).collect(java.util.stream.Collectors.joining(","));
        }
        throw error("INVALID_INVENTORY_SORT", false);
    }

    protected static String enumValue(String value, String field, Enum<?>[] allowed) {
        if (value == null || value.isBlank()) throw error("INVALID_REQUEST", false);
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (Enum<?> candidate : allowed) if (candidate.name().equals(normalized)) return normalized;
        throw error("INVALID_REQUEST", false);
    }

    protected static String hash(String operation, Object... values) {
        String canonical = operation + "|" + java.util.Arrays.stream(values)
                .map(value -> value == null ? "<null>" : String.valueOf(value).trim())
                .reduce((left, right) -> left + "|" + right).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    protected static Instant instant(ResultSet rs, int index) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(index);
        return value == null ? null : value.toInstant();
    }

    protected static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
    protected static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }

    protected static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException exception) { throw error("INVALID_REQUEST", false); }
    }

    protected static LogisticsException error(String code, boolean notFound) {
        return new LogisticsException(code, notFound);
    }

    protected record BusinessCard(String clientCode, String clientName, String priority, String deliveryArea) { }

    protected record DispatchRow(UUID id, String dispatchNumber, UUID reservationId, UUID salesOrderId,
                                 UUID clientAccountId, String status, String destination, String clientCode,
                                 String clientName, String deliveryArea, String priority, Instant windowStart,
                                 Instant windowEnd, Instant eta, UUID responsibleMembershipId,
                                 String responsibleDisplayName, String vehicleReference, String routeName,
                                 BigDecimal temperatureMin, BigDecimal temperatureMax, String temperatureUnit,
                                 String temperatureStatus, long version, Instant updatedAt, String podId,
                                 String podStatus, String salesOrderNumber, String lastAttemptId, int lastAttemptNumber,
                                 String lastAttemptStatus, String lastAttemptFailureReason, Instant lastAttemptOccurredAt,
                                 String continuationDeliveryId, String continuationDeliveryStatus, UUID tenantId,
                                 UUID workspaceId) { }

    protected record Idem(String resource, String hash) { }
}
