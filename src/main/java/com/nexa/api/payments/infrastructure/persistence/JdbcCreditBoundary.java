package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.CreditExposureQuery;
import com.nexa.api.payments.application.publicapi.CreditReservationCommands;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcCreditBoundary implements CreditExposureQuery, CreditReservationCommands {
    private final JdbcTemplate jdbc;

    public JdbcCreditBoundary(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CreditExposureSnapshot find(String tenantId, String workspaceId, String customerAccountId, String currency) {
        BigDecimal outstanding = jdbc.queryForObject(
                "select coalesce(sum(amount-amount_paid),0) from payments.receivable where tenant_id=? and workspace_id=? "
                        + "and client_account_id=? and currency=? and status in ('OPEN','PARTIALLY_PAID','OVERDUE')",
                BigDecimal.class, uuid(tenantId), uuid(workspaceId), uuid(customerAccountId), currency);
        BigDecimal reserved = jdbc.queryForObject(
                "select coalesce(sum(reserved_exposure),0) from payments.credit_account where tenant_id=? and workspace_id=? "
                        + "and client_account_id=? and currency=? and status='ACTIVE'",
                BigDecimal.class, uuid(tenantId), uuid(workspaceId), uuid(customerAccountId), currency);
        return new CreditExposureSnapshot(outstanding, reserved);
    }

    @Override
    public void reserve(UUID tenantId, UUID workspaceId, UUID customerAccountId, UUID purchaseRequestId,
                        BigDecimal amount, String currency, BigDecimal creditLimit, Instant now) {
        if (amount == null || amount.signum() <= 0 || currency == null || currency.isBlank()) {
            throw new IllegalStateException("Credit commitment requires priced lines");
        }
        jdbc.update("insert into payments.credit_account (id,tenant_id,workspace_id,client_account_id,currency,credit_limit,created_at,updated_at) "
                        + "values (md5(? || ':' || ?)::uuid,?,?,?,?,?,?,?) "
                        + "on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing",
                customerAccountId.toString(), currency, tenantId, workspaceId, customerAccountId, currency,
                value(creditLimit), timestamp(now), timestamp(now));
        CreditAccountRow account = jdbc.query(
                "select id,credit_limit,credit_exposure,reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? "
                        + "and client_account_id=? and currency=? and status='ACTIVE' for update",
                (rs, ignored) -> new CreditAccountRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4)), tenantId, workspaceId, customerAccountId, currency)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Client credit account is not configured"));
        CreditExposureSnapshot exposure = find(tenantId.toString(), workspaceId.toString(), customerAccountId.toString(), currency);
        BigDecimal available = account.limit().subtract(account.exposure()).subtract(account.reserved())
                .subtract(exposure.outstandingReceivables());
        if (available.compareTo(amount) < 0) throw new IllegalStateException("Credit limit exceeded");
        CreditReservationRow existing = jdbc.query(
                "select id,credit_account_id,amount,status from payments.credit_reservation where tenant_id=? and workspace_id=? "
                        + "and purchase_request_id=? for update",
                (rs, ignored) -> new CreditReservationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getString(4)), tenantId, workspaceId, purchaseRequestId)
                .stream().findFirst().orElse(null);
        if (existing != null && "RESERVED".equals(existing.status())) return;
        if (existing != null && !"RELEASED".equals(existing.status())) {
            throw new IllegalStateException("Credit commitment is no longer reusable");
        }
        if (existing == null) {
            jdbc.update("insert into payments.credit_reservation (id,tenant_id,workspace_id,credit_account_id,purchase_request_id,amount,status,idempotency_key,created_at) "
                            + "values (?,?,?,?,?,?,'RESERVED',?,?)",
                    UUID.randomUUID(), tenantId, workspaceId, account.id(), purchaseRequestId, amount,
                    "purchase-request:" + purchaseRequestId, timestamp(now));
        } else {
            jdbc.update("update payments.credit_reservation set credit_account_id=?,amount=?,status='RESERVED',released_at=null,created_at=? "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    account.id(), amount, timestamp(now), tenantId, workspaceId, existing.id());
        }
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure+?,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and credit_exposure+reserved_exposure+?<=credit_limit-?",
                amount, timestamp(now), tenantId, workspaceId, account.id(), amount,
                exposure.outstandingReceivables()) != 1) {
            throw new IllegalStateException("Credit limit exceeded");
        }
    }

    @Override
    public void release(UUID tenantId, UUID workspaceId, UUID purchaseRequestId) {
        CreditReservationRow reservation = reservation(tenantId, workspaceId, purchaseRequestId);
        if (reservation == null || !"RESERVED".equals(reservation.status())) return;
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure-?,version=version+1,updated_at=current_timestamp "
                        + "where tenant_id=? and workspace_id=? and id=? and reserved_exposure>=?",
                reservation.amount(), tenantId, workspaceId, reservation.creditAccountId(), reservation.amount()) != 1) {
            throw new IllegalStateException("Credit reservation balance is inconsistent");
        }
        jdbc.update("update payments.credit_reservation set status='RELEASED',released_at=current_timestamp "
                        + "where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                tenantId, workspaceId, reservation.id());
    }

    @Override
    public void linkSalesOrder(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, UUID salesOrderId) {
        jdbc.update("update payments.credit_reservation set sales_order_id=? where tenant_id=? and workspace_id=? "
                        + "and purchase_request_id=? and status='RESERVED'",
                salesOrderId, tenantId, workspaceId, purchaseRequestId);
    }

    private CreditReservationRow reservation(UUID tenantId, UUID workspaceId, UUID purchaseRequestId) {
        return jdbc.query("select id,credit_account_id,amount,status from payments.credit_reservation where tenant_id=? and workspace_id=? "
                        + "and purchase_request_id=? for update",
                (rs, ignored) -> new CreditReservationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getString(4)), tenantId, workspaceId, purchaseRequestId)
                .stream().findFirst().orElse(null);
    }

    private static BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private record CreditAccountRow(UUID id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) { }
    private record CreditReservationRow(UUID id, UUID creditAccountId, BigDecimal amount, String status) { }
}
