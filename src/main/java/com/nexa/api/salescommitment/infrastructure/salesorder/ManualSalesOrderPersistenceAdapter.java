package com.nexa.api.salescommitment.infrastructure.salesorder;

import com.nexa.api.salescommitment.application.exception.SalesIdempotencyPayloadConflictException;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderLineView;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.salescommitment.domain.model.salesorder.ManualSalesOrder;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderId;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderNumber;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persists manual orders using the primary sales order table and manual idempotency contract. */
@Repository
@Profile("!test")
public class ManualSalesOrderPersistenceAdapter implements ManualSalesOrderPersistencePort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ManualSalesOrderPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<ManualSalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId,
                                                            String idempotencyKey, String requestHash) {
        // The idempotency row cannot be claimed before the order exists because its
        // foreign key is intentionally NOT NULL. Serialize the complete request
        // transaction on the deterministic key before any order side effect.
        String lockKey = tenantId + ":" + workspaceId + ":" + actorMembershipId + ":" + idempotencyKey;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, lockKey);
        return jdbc.query("select request_hash,sales_order_id from sales.manual_order_idempotency where tenant_id=? and workspace_id=? "
                        + "and actor_membership_id=? and idempotency_key=? for update",
                (org.springframework.jdbc.core.ResultSetExtractor<Optional<ManualSalesOrderView>>) rs -> {
                    if (!rs.next()) return Optional.empty();
                    if (!requestHash.equalsIgnoreCase(rs.getString(1))) throw new SalesIdempotencyPayloadConflictException();
                    return find(tenantId, workspaceId, rs.getObject(2).toString());
                }, uuid(tenantId), uuid(workspaceId), uuid(actorMembershipId), idempotencyKey);
    }

    @Override
    public SalesOrderIdentity nextIdentity(String tenantId, String workspaceId) {
        UUID tenant = uuid(tenantId), workspace = uuid(workspaceId);
        int year = Year.now(ZoneOffset.UTC).getValue();
        jdbc.update("insert into sales.sales_order_sequence (tenant_id,workspace_id,order_year,next_value) values (?,?,?,1) on conflict do nothing",
                tenant, workspace, year);
        Long sequence = jdbc.queryForObject("select next_value from sales.sales_order_sequence where tenant_id=? and workspace_id=? and order_year=? for update",
                Long.class, tenant, workspace, year);
        jdbc.update("update sales.sales_order_sequence set next_value=? where tenant_id=? and workspace_id=? and order_year=?",
                sequence + 1, tenant, workspace, year);
        return new SalesOrderIdentity(new SalesOrderId(UUID.randomUUID().toString()),
                new SalesOrderNumber(String.format("SO-%04d-%06d", year, sequence)));
    }

    @Override
    public Optional<ManualSalesOrderView> findById(String tenantId, String workspaceId, String salesOrderId) {
        return find(tenantId, workspaceId, salesOrderId);
    }

    @Override
    public ManualSalesOrderView save(ManualSalesOrder order, String actorMembershipId, String idempotencyKey,
                                     String requestHash, long nowEpochMillis) {
        UUID id = uuid(order.id().value());
        String snapshot = json(order.snapshot());
        jdbc.update("insert into sales.sales_order (id,tenant_id,workspace_id,number,client_account_id,created_by_membership_id,"
                        + "buyer_membership_id,source_purchase_request_id,order_source,origin_type,priority,requested_delivery_date,delivery_snapshot,"
                        + "payment_option,notes,currency,total_amount,status,created_at,updated_at,version,delivery_address_snapshot,route_snapshot,"
                        + "warehouse_selection_snapshot,commercial_snapshot) values (?,?,?,?,?,?,?,null,'MANUAL','MANUAL',?,?,?,?,?,?,?,'PENDING',?,?,0,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                id, order.tenantId().value(), order.workspaceId().value(), order.number().value(), uuid(order.clientAccountId().value()),
                uuid(actorMembershipId), uuid(actorMembershipId), order.priority().name(), order.requestedDeliveryDate(), snapshot,
                order.snapshot().payment().option().name(), order.snapshot().notes(), order.snapshot().payment().currency(), order.total(),
                timestamp(nowEpochMillis), timestamp(nowEpochMillis), json(order.snapshot().delivery().address()),
                json(order.snapshot().delivery().route()), json(order.snapshot().delivery().warehouse()), json(order.snapshot().commercial()));
        for (var line : order.lines()) {
            jdbc.update("insert into sales.sales_order_line (id,sales_order_id,catalog_item_id,sku_id,product_family_id,sku_code_snapshot,product_family_code_snapshot,item_name_snapshot,presentation_snapshot,quantity,unit,"
                            + "unit_price_amount,unit_price_currency,line_subtotal,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), id, line.catalogItemId(), line.sellableSkuId(), line.productFamilyId(), line.skuCodeSnapshot(), line.productFamilyCodeSnapshot(), line.itemNameSnapshot(), line.presentationSnapshot(), line.quantity(),
                    line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.lineSubtotal(), timestamp(nowEpochMillis));
        }
        jdbc.update("insert into sales.sales_order_event (id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,to_status,occurred_at) "
                        + "values (?,?,?,?,?,'ORDER_CREATED','PENDING',?)",
                UUID.randomUUID(), id, order.tenantId().value(), order.workspaceId().value(), uuid(actorMembershipId), timestamp(nowEpochMillis));
        int inserted = jdbc.update("insert into sales.manual_order_idempotency (tenant_id,workspace_id,actor_membership_id,idempotency_key,request_hash,sales_order_id,created_at) "
                        + "values (?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,actor_membership_id,idempotency_key) do nothing",
                order.tenantId().value(), order.workspaceId().value(), uuid(actorMembershipId), idempotencyKey, requestHash, id, timestamp(nowEpochMillis));
        if (inserted != 1) return findByIdempotency(order.tenantId().toString(), order.workspaceId().toString(), actorMembershipId,
                idempotencyKey, requestHash).orElseThrow();
        return find(order.tenantId().toString(), order.workspaceId().toString(), order.id().value()).orElseThrow();
    }

    private Optional<ManualSalesOrderView> find(String tenantId, String workspaceId, String id) {
        return jdbc.query("select o.id,o.number,o.tenant_id,o.workspace_id,o.client_account_id,o.created_by_membership_id,o.priority,"
                        + "o.delivery_snapshot,o.currency,o.total_amount,o.status,o.created_at,o.updated_at,o.version from sales.sales_order o "
                        + "where o.tenant_id=? and o.workspace_id=? and o.id=? and o.order_source='MANUAL'",
                (org.springframework.jdbc.core.ResultSetExtractor<Optional<ManualSalesOrderView>>) rs -> rs.next()
                        ? Optional.of(view(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId), uuid(id));
    }

    private ManualSalesOrderView view(ResultSet rs) throws java.sql.SQLException {
        String id = rs.getObject(1).toString();
        var snapshot = read(rs.getString(8), com.nexa.api.salescommitment.domain.model.salesorder.ManualSalesOrderSnapshot.class);
        List<SalesOrderLineView> lines = jdbc.query("select catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,line_subtotal,sku_id,product_family_id,sku_code_snapshot,product_family_code_snapshot "
                        + "from sales.sales_order_line where sales_order_id=? order by created_at,id",
                (line, row) -> new SalesOrderLineView(line.getString(1), line.getString(2), line.getString(3), line.getBigDecimal(4),
                        line.getString(5), line.getBigDecimal(6), line.getString(7), line.getBigDecimal(8), stringUuid(line.getObject(9)),
                        stringUuid(line.getObject(10)), line.getString(11), line.getString(12)), uuid(id));
        return new ManualSalesOrderView(id, rs.getString(2), rs.getObject(3).toString(), rs.getObject(4).toString(),
                rs.getObject(5).toString(), rs.getObject(6).toString(), PurchaseRequestPriority.from(rs.getString(7)),
                snapshot.delivery().requestedDate(), snapshot, rs.getString(9), rs.getBigDecimal(10), rs.getString(11),
                rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant(), rs.getLong(14), lines);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to serialize sales snapshot", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to read sales snapshot", exception); }
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static String stringUuid(Object value) { return value == null ? null : value.toString(); }
    private static Timestamp timestamp(long epochMillis) { return Timestamp.from(Instant.ofEpochMilli(epochMillis)); }
}
