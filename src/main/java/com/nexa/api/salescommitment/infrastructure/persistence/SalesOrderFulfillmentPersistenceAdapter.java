package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sales-owned snapshot and status adapter for the fulfillment boundary. */
@Repository
@Profile("!test")
public class SalesOrderFulfillmentPersistenceAdapter
        implements SalesOrderFulfillmentQuery, SalesOrderFulfillmentCommands {
    private final JdbcTemplate jdbc;

    public SalesOrderFulfillmentPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Snapshot get(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return load(tenantId, workspaceId, salesOrderId, false);
    }

    @Override
    public Snapshot getForUpdate(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return load(tenantId, workspaceId, salesOrderId, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markInFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                                  UUID actorMembershipId, Instant now) {
        Snapshot current = getForUpdate(tenantId, workspaceId, salesOrderId);
        if ("IN_FULFILLMENT".equals(current.status()) || "PARTIALLY_DELIVERED".equals(current.status())) return;
        requireStatus(current, "CONFIRMED");
        transition(tenantId, workspaceId, salesOrderId, current, "IN_FULFILLMENT", actorMembershipId,
                now, "Fulfillment started");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markPartiallyDelivered(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                                       UUID actorMembershipId, Instant now, String reason) {
        Snapshot current = getForUpdate(tenantId, workspaceId, salesOrderId);
        if ("PARTIALLY_DELIVERED".equals(current.status())) return;
        if ("COMPLETED".equals(current.status())) throw new CommercialBusinessException("SALES_ORDER_TRANSITION_INVALID");
        if ("CANCELLED".equals(current.status()) || "REJECTED".equals(current.status())) {
            throw new CommercialBusinessException("SALES_ORDER_TRANSITION_INVALID");
        }
        transition(tenantId, workspaceId, salesOrderId, current, "PARTIALLY_DELIVERED", actorMembershipId,
                now, reason == null || reason.isBlank() ? "Partial delivery recorded" : reason);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCompleted(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                              UUID actorMembershipId, Instant now, String reason,
                              BigDecimal unresolvedQuantity) {
        if (unresolvedQuantity == null || unresolvedQuantity.signum() != 0) {
            throw new CommercialBusinessException("SALES_ORDER_TRANSITION_INVALID");
        }
        Snapshot current = getForUpdate(tenantId, workspaceId, salesOrderId);
        if ("COMPLETED".equals(current.status())) return;
        if ("CANCELLED".equals(current.status()) || "REJECTED".equals(current.status())) {
            throw new CommercialBusinessException("SALES_ORDER_TRANSITION_INVALID");
        }
        transition(tenantId, workspaceId, salesOrderId, current, "COMPLETED", actorMembershipId,
                now, reason == null || reason.isBlank() ? "Fulfillment commercially resolved" : reason);
    }

    private Snapshot load(UUID tenantId, UUID workspaceId, UUID salesOrderId, boolean forUpdate) {
        String lock = forUpdate ? " for update" : "";
        Snapshot order = jdbc.query("select id,number,client_account_id,status,payment_option,commercial_commitment_id,delivery_snapshot,currency,total_amount,version from sales.sales_order where tenant_id=? and workspace_id=? and id=?" + lock,
                (rs, row) -> new Snapshot(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("client_account_id", UUID.class), rs.getString("status"),
                        rs.getString("payment_option"), rs.getObject("commercial_commitment_id", UUID.class),
                        rs.getString("delivery_snapshot"), rs.getString("currency"),
                        rs.getBigDecimal("total_amount"), rs.getLong("version"), List.of()),
                tenantId, workspaceId, salesOrderId).stream().findFirst()
                .orElseThrow(() -> new CommercialBusinessException("SALES_ORDER_NOT_FOUND"));
        List<Line> lines = jdbc.query("select id,sku_id,catalog_item_id,quantity,unit,unit_price_amount,unit_price_currency from sales.sales_order_line where sales_order_id=? order by id",
                (rs, row) -> new Line(rs.getObject("id", UUID.class), rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getBigDecimal("unit_price_amount"), rs.getString("unit_price_currency")), salesOrderId);
        return new Snapshot(order.id(), order.number(), order.clientAccountId(), order.status(), order.paymentOption(),
                order.commercialCommitmentId(), order.destinationSnapshot(), order.currency(), order.total(),
                order.version(), lines);
    }

    private void transition(UUID tenantId, UUID workspaceId, UUID salesOrderId, Snapshot current,
                            String target, UUID actorMembershipId, Instant now, String reason) {
        Instant occurredAt = now == null ? Instant.now() : now;
        if (jdbc.update("update sales.sales_order set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                target, Timestamp.from(occurredAt), tenantId, workspaceId, salesOrderId, current.version()) != 1) {
            throw new CommercialBusinessException("SALES_ORDER_CONCURRENCY_CONFLICT");
        }
        UUID eventId = UUID.nameUUIDFromBytes((salesOrderId + "|" + target).getBytes(StandardCharsets.UTF_8));
        jdbc.update("insert into sales.sales_order_event(id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,reason,occurred_at) values (?,?,?,?,?,?,?,?,?,?) on conflict (id) do nothing",
                eventId, salesOrderId, tenantId, workspaceId, actorMembershipId, "SALES_ORDER_STATUS_CHANGED",
                current.status(), target, bounded(reason), Timestamp.from(occurredAt));
    }

    private static void requireStatus(Snapshot snapshot, String expected) {
        if (!expected.equals(snapshot.status())) throw new CommercialBusinessException("SALES_ORDER_TRANSITION_INVALID");
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "Fulfillment lifecycle transition";
        String trimmed = value.trim();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000);
    }
}
