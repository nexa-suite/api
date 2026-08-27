package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Durable receivable posting owned by BC-07; called only after Sales confirmation. */
@Repository
@Profile("!test")
public class JdbcReceivableCommands implements ReceivableCommands {
    private final JdbcTemplate jdbc;

    public JdbcReceivableCommands(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID postForSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                                  UUID clientAccountId, BigDecimal amount, String currency, Instant now) {
        if (salesOrderId == null || clientAccountId == null || amount == null || amount.signum() <= 0
                || currency == null || currency.isBlank()) {
            throw new IllegalStateException("Receivable snapshot is invalid");
        }
        String normalizedCurrency = currency.trim().toUpperCase(java.util.Locale.ROOT);
        ReceivableRow existing = jdbc.query(
                "select id,client_account_id,amount,currency from payments.receivable "
                        + "where tenant_id=? and workspace_id=? and subject_type='SALES_ORDER' and subject_id=? for update",
                (rs, row) -> new ReceivableRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getString(4)), tenantId, workspaceId, salesOrderId)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            ensureCanonical(existing, clientAccountId, amount, normalizedCurrency);
            settleReservation(tenantId, workspaceId, salesOrderId, now);
            return existing.id();
        }

        UUID receivableId = UUID.randomUUID();
        Instant occurredAt = now == null ? Instant.now() : now;
        String number = "AR-" + occurredAt.toEpochMilli() + "-" + receivableId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        int inserted = jdbc.update("insert into payments.receivable "
                        + "(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,receivable_number,currency,amount,due_at,status,created_at,updated_at) "
                        + "values (?,?,?,?, 'SALES_ORDER',?,?,?,?,?,'OPEN',?,?) on conflict (tenant_id,workspace_id,subject_type,subject_id) do nothing",
                receivableId, tenantId, workspaceId, clientAccountId, salesOrderId, number, normalizedCurrency,
                amount, Timestamp.from(occurredAt.plusSeconds(30L * 24 * 60 * 60)), Timestamp.from(occurredAt), Timestamp.from(occurredAt));
        if (inserted == 0) {
            return postForSalesOrder(tenantId, workspaceId, salesOrderId, clientAccountId, amount, normalizedCurrency, occurredAt);
        }

        settleReservation(tenantId, workspaceId, salesOrderId, occurredAt);
        CanonicalOutbox.append(jdbc, "RECEIVABLE_CREATED", "Receivable", receivableId, tenantId, workspaceId,
                occurredAt, "receivable-" + receivableId, null, "1.0", Map.of(
                        "receivableId", receivableId, "subjectType", "SALES_ORDER", "subjectId", salesOrderId,
                        "amount", amount, "currency", normalizedCurrency));
        return receivableId;
    }

    private void settleReservation(UUID tenantId, UUID workspaceId, UUID salesOrderId, Instant now) {
        CreditReservationLink link = jdbc.query(
                "select id,credit_account_id,amount from payments.credit_reservation "
                        + "where tenant_id=? and workspace_id=? and sales_order_id=? and status='RESERVED' for update",
                (rs, row) -> new CreditReservationLink(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3)),
                tenantId, workspaceId, salesOrderId).stream().findFirst().orElse(null);
        if (link == null) return;
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure-?,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and reserved_exposure>=?",
                link.amount(), Timestamp.from(now), tenantId, workspaceId, link.creditAccountId(), link.amount()) != 1) {
            throw new IllegalStateException("Credit reservation balance is inconsistent");
        }
        jdbc.update("update payments.credit_reservation set status='CONSUMED',released_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                Timestamp.from(now), tenantId, workspaceId, link.id());
    }

    private static void ensureCanonical(ReceivableRow existing, UUID clientAccountId, BigDecimal amount, String currency) {
        if (!clientAccountId.equals(existing.clientAccountId()) || existing.amount().compareTo(amount) != 0
                || !currency.equalsIgnoreCase(existing.currency())) {
            throw new IllegalStateException("Sales Order receivable snapshot is immutable");
        }
    }

    private record ReceivableRow(UUID id, UUID clientAccountId, BigDecimal amount, String currency) { }
    private record CreditReservationLink(UUID id, UUID creditAccountId, BigDecimal amount) { }
}
