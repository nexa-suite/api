package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.CreditExposureQuery;
import com.nexa.api.payments.application.publicapi.CreditReservationCommands;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
        CreditAccountRow account = jdbc.query(
                "select id,credit_limit,credit_exposure,reserved_exposure from payments.credit_account "
                        + "where tenant_id=? and workspace_id=? and client_account_id=? and currency=? and status='ACTIVE'",
                (rs, ignored) -> new CreditAccountRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4)),
                uuid(tenantId), uuid(workspaceId), uuid(customerAccountId), currency)
                .stream().findFirst().orElse(null);
        if (account == null) return CreditExposureSnapshot.unavailable(currency);
        BigDecimal outstanding = jdbc.queryForObject(
                "select coalesce(sum(amount-amount_paid),0) from payments.receivable where tenant_id=? and workspace_id=? "
                        + "and client_account_id=? and currency=? and status in ('OPEN','PARTIALLY_PAID','OVERDUE')",
                BigDecimal.class, uuid(tenantId), uuid(workspaceId), uuid(customerAccountId), currency);
        return new CreditExposureSnapshot(currency, account.limit(), account.exposure(), outstanding,
                account.reserved(), true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID tenantId, UUID workspaceId, UUID customerAccountId, UUID purchaseRequestId,
                        BigDecimal amount, String currency, Instant now) {
        reserveForCommitment(tenantId, workspaceId, customerAccountId, null, purchaseRequestId, null, amount, currency, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveForCommitment(UUID tenantId, UUID workspaceId, UUID customerAccountId,
                                     UUID commercialCommitmentId, UUID purchaseRequestId, UUID salesOrderId,
                                     BigDecimal amount, String currency, Instant now) {
        if (amount == null || amount.signum() <= 0 || currency == null || currency.isBlank()) {
            throw new IllegalStateException("Credit commitment requires priced lines");
        }
        if (commercialCommitmentId == null && purchaseRequestId == null) {
            throw new IllegalStateException("Credit commitment reference is required");
        }
        CreditReservationRow existing = reservation(tenantId, workspaceId, commercialCommitmentId, purchaseRequestId);
        jdbc.update("insert into payments.credit_account "
                        + "(id,tenant_id,workspace_id,client_account_id,currency,credit_limit,created_at,updated_at) "
                        + "select md5(c.id::text || ':' || c.credit_currency)::uuid,c.tenant_id,c.workspace_id,c.id,"
                        + "c.credit_currency,c.credit_limit,?,? from sales.client_account c "
                        + "where c.tenant_id=? and c.workspace_id=? and c.id=? and c.credit_currency=? "
                        + "on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing",
                timestamp(now), timestamp(now), tenantId, workspaceId, customerAccountId, currency);
        CreditAccountRow account = jdbc.query(
                "select id,credit_limit,credit_exposure,reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? "
                        + "and client_account_id=? and currency=? and status='ACTIVE' for update",
                (rs, ignored) -> new CreditAccountRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4)), tenantId, workspaceId, customerAccountId, currency)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Client credit account is not configured"));
        if (existing != null && "RESERVED".equals(existing.status())) {
            if (!account.id().equals(existing.creditAccountId())
                    || existing.amount().compareTo(amount) != 0
                    || !same(existing.commercialCommitmentId(), commercialCommitmentId)
                    || !same(existing.purchaseRequestId(), purchaseRequestId)
                    || !same(existing.salesOrderId(), salesOrderId)) {
                throw new IllegalStateException("Credit reservation payload conflict");
            }
            return;
        }
        if (existing != null && !"RELEASED".equals(existing.status())) {
            throw new IllegalStateException("Credit commitment is no longer reusable");
        }
        CreditExposureSnapshot exposure = find(tenantId.toString(), workspaceId.toString(), customerAccountId.toString(), currency);
        if (exposure.availableCredit().compareTo(amount) < 0) throw new IllegalStateException("Credit limit exceeded");
        String idempotencyKey = commercialCommitmentId == null
                ? "purchase-request:" + purchaseRequestId
                : "commercial-commitment:" + commercialCommitmentId;
        if (existing == null) {
            jdbc.update("insert into payments.credit_reservation (id,tenant_id,workspace_id,credit_account_id,purchase_request_id,sales_order_id,commercial_commitment_id,amount,status,idempotency_key,created_at) "
                            + "values (?,?,?,?,?,?,?,?,'RESERVED',?,?)",
                    UUID.randomUUID(), tenantId, workspaceId, account.id(), purchaseRequestId, salesOrderId,
                    commercialCommitmentId, amount, idempotencyKey, timestamp(now));
        } else {
            jdbc.update("update payments.credit_reservation set credit_account_id=?,purchase_request_id=?,sales_order_id=?,commercial_commitment_id=?,amount=?,status='RESERVED',released_at=null,created_at=? "
                            + "where tenant_id=? and workspace_id=? and id=?",
                    account.id(), purchaseRequestId, salesOrderId, commercialCommitmentId, amount, timestamp(now), tenantId, workspaceId, existing.id());
        }
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure+?,version=version+1,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and credit_exposure+reserved_exposure+?<=credit_limit-?",
                amount, timestamp(now), tenantId, workspaceId, account.id(), amount,
                exposure.outstandingReceivables()) != 1) {
            throw new IllegalStateException("Credit limit exceeded");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID tenantId, UUID workspaceId, UUID purchaseRequestId) {
        releaseForCommitment(tenantId, workspaceId, null, purchaseRequestId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForCommitment(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId,
                                     UUID purchaseRequestId) {
        CreditReservationRow reservation = reservation(tenantId, workspaceId, commercialCommitmentId, purchaseRequestId);
        if (reservation == null || !"RESERVED".equals(reservation.status())) return;
        UUID accountId = jdbc.query("select id from payments.credit_account where tenant_id=? and workspace_id=? and id=? and status='ACTIVE' for update",
                (org.springframework.jdbc.core.ResultSetExtractor<UUID>) rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenantId, workspaceId, reservation.creditAccountId());
        if (accountId == null) throw new IllegalStateException("Credit account is not configured");
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
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkSalesOrder(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, UUID salesOrderId) {
        linkSalesOrderForCommitment(tenantId, workspaceId, null, purchaseRequestId, salesOrderId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkSalesOrderForCommitment(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId,
                                            UUID purchaseRequestId, UUID salesOrderId) {
        if (commercialCommitmentId == null && purchaseRequestId == null) return;
        CreditReservationRow reservation = reservation(tenantId, workspaceId, commercialCommitmentId, purchaseRequestId);
        if (reservation == null || !"RESERVED".equals(reservation.status())) return;
        if (reservation.salesOrderId() != null) {
            if (!same(reservation.salesOrderId(), salesOrderId)) {
                throw new IllegalStateException("Credit reservation Sales Order link conflict");
            }
            return;
        }
        jdbc.update("update payments.credit_reservation set sales_order_id=? where tenant_id=? and workspace_id=? and id=? and status='RESERVED' and sales_order_id is null",
                salesOrderId, tenantId, workspaceId, reservation.id());
    }

    private CreditReservationRow reservation(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId, UUID purchaseRequestId) {
        String predicate = commercialCommitmentId == null ? "purchase_request_id=?" : "commercial_commitment_id=?";
        UUID reference = commercialCommitmentId == null ? purchaseRequestId : commercialCommitmentId;
        if (reference == null) return null;
        return jdbc.query("select id,credit_account_id,amount,status,commercial_commitment_id,purchase_request_id,sales_order_id from payments.credit_reservation where tenant_id=? and workspace_id=? "
                        + "and " + predicate + " for update",
                (rs, ignored) -> new CreditReservationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3), rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)), tenantId, workspaceId, reference)
                .stream().findFirst().orElse(null);
    }

    private static boolean same(UUID left, UUID right) { return left == null ? right == null : left.equals(right); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private record CreditAccountRow(UUID id, BigDecimal limit, BigDecimal exposure, BigDecimal reserved) { }
    private record CreditReservationRow(UUID id, UUID creditAccountId, BigDecimal amount, String status,
                                        UUID commercialCommitmentId, UUID purchaseRequestId, UUID salesOrderId) { }
}
