package com.nexa.api.sales.infrastructure.commitment;

import com.nexa.api.sales.application.port.CommercialCommitmentPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Durable V1 commitment projection kept behind an application port. */
@Repository
@Profile("!test")
public class CommercialCommitmentPersistenceAdapter implements CommercialCommitmentPort {
    private final JdbcTemplate jdbc;

    public CommercialCommitmentPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void activateForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId) {
        Instant now = Instant.now();
        UUID clientAccountId = jdbc.queryForObject(
                "select client_account_id from sales.purchase_request where tenant_id=? and workspace_id=? and id=? for update",
                UUID.class, tenantId, workspaceId, purchaseRequestId);
        UUID commitmentId = jdbc.query(
                "select id from sales.commercial_commitment where tenant_id=? and workspace_id=? and purchase_request_id=? for update",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, workspaceId, purchaseRequestId);
        if (commitmentId == null) {
            commitmentId = UUID.randomUUID();
            jdbc.update("insert into sales.commercial_commitment (id,tenant_id,workspace_id,purchase_request_id,client_account_id,status,created_at,updated_at,version) values (?,?,?,?,?,'ACTIVE',?,?,0)",
                    commitmentId, tenantId, workspaceId, purchaseRequestId, clientAccountId, Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbc.update("update sales.commercial_commitment set status='ACTIVE',released_at=null,release_reason=null,converted_at=null,sales_order_id=null,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status <> 'CONVERTED'",
                    Timestamp.from(now), tenantId, workspaceId, commitmentId);
        }

        jdbc.update("delete from sales.commercial_commitment_line where tenant_id=? and workspace_id=? and commitment_id=?",
                tenantId, workspaceId, commitmentId);
        UUID activeCommitmentId = commitmentId;
        jdbc.query("select l.id,coalesce(l.sku_id,s.id),s.sku_code,l.quantity,l.unit,l.unit_price_currency "
                        + "from sales.purchase_request_line l "
                        + "join sales.purchase_request r on r.id=l.purchase_request_id "
                        + "left join catalog_management.sellable_sku s on s.tenant_id=r.tenant_id and s.workspace_id=r.workspace_id "
                        + "and (s.id=l.sku_id or s.legacy_catalog_item_id=l.catalog_item_id or s.sku_code=l.catalog_item_id) "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? order by l.created_at,l.id",
                (rs, n) -> new CommitmentLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)), tenantId, workspaceId, purchaseRequestId)
                .forEach(line -> {
                    if (line.skuId() == null) throw new IllegalStateException("Purchase request line is not mapped to a sellable SKU");
                    jdbc.update("insert into sales.commercial_commitment_line (id,tenant_id,workspace_id,commitment_id,purchase_request_line_id,sku_id,sku_code_snapshot,quantity,unit,currency,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                            UUID.randomUUID(), tenantId, workspaceId, activeCommitmentId, line.requestLineId(), line.skuId(), line.skuCode(),
                            line.quantity(), line.unit(), line.currency(), Timestamp.from(now));
                });
        String paymentOption = jdbc.queryForObject("select payment_option from sales.purchase_request where tenant_id=? and workspace_id=? and id=?",
                String.class, tenantId, workspaceId, purchaseRequestId);
        if ("CREDIT_LINE".equalsIgnoreCase(paymentOption)) {
            reserveCredit(tenantId, workspaceId, clientAccountId, purchaseRequestId, now);
        }
    }

    @Override
    @Transactional
    public void releaseForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, String reason) {
        jdbc.update("update sales.commercial_commitment set status='RELEASED',released_at=current_timestamp,release_reason=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and purchase_request_id=? and status='ACTIVE'",
                normalizeReason(reason), tenantId, workspaceId, purchaseRequestId);
        releaseCredit(tenantId, workspaceId, purchaseRequestId);
    }

    @Override
    @Transactional
    public void convertForSalesOrder(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, UUID salesOrderId) {
        int changed = jdbc.update("update sales.commercial_commitment set status='CONVERTED',sales_order_id=?,converted_at=current_timestamp,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and purchase_request_id=? and status='ACTIVE'",
                salesOrderId, tenantId, workspaceId, purchaseRequestId);
        if (changed == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.commercial_commitment where tenant_id=? and workspace_id=? and purchase_request_id=? and status='CONVERTED' and sales_order_id=?)", Boolean.class,
                tenantId, workspaceId, purchaseRequestId, salesOrderId))) {
            throw new IllegalStateException("Active commercial commitment is required before Sales Order conversion");
        }
        jdbc.update("update payments.credit_reservation set sales_order_id=? where tenant_id=? and workspace_id=? and purchase_request_id=? and status='RESERVED'",
                salesOrderId, tenantId, workspaceId, purchaseRequestId);
    }

    private void reserveCredit(UUID tenantId, UUID workspaceId, UUID clientAccountId, UUID purchaseRequestId, Instant now) {
        AmountRow amount = jdbc.query("select coalesce(sum(quantity * unit_price_amount),0), max(unit_price_currency) from sales.purchase_request_line where purchase_request_id=?",
                (rs, n) -> new AmountRow(rs.getBigDecimal(1), rs.getString(2)), purchaseRequestId).stream()
                .findFirst().orElse(new AmountRow(java.math.BigDecimal.ZERO, null));
        if (amount.amount().signum() <= 0 || amount.currency() == null) throw new IllegalStateException("Credit commitment requires priced lines");
        jdbc.update("insert into payments.credit_account (id,tenant_id,workspace_id,client_account_id,currency,credit_limit,created_at,updated_at) "
                        + "select md5(c.id::text || ':' || c.credit_currency)::uuid,c.tenant_id,c.workspace_id,c.id,c.credit_currency,c.credit_limit,?,? "
                        + "from sales.client_account c where c.tenant_id=? and c.workspace_id=? and c.id=? "
                        + "on conflict (tenant_id,workspace_id,client_account_id,currency) do nothing",
                Timestamp.from(now), Timestamp.from(now), tenantId, workspaceId, clientAccountId);
        CreditAccountRow account = jdbc.query("select id,credit_limit,reserved_exposure from payments.credit_account where tenant_id=? and workspace_id=? and client_account_id=? and currency=? and status='ACTIVE' for update",
                (rs, n) -> rs.next() ? new CreditAccountRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3)) : null,
                tenantId, workspaceId, clientAccountId, amount.currency()).stream().findFirst().orElseThrow(() -> new IllegalStateException("Client credit account is not configured"));
        java.math.BigDecimal outstanding = jdbc.queryForObject("select coalesce(sum(amount-amount_paid),0) from payments.receivable where tenant_id=? and workspace_id=? and client_account_id=? and currency=? and status in ('OPEN','PARTIALLY_PAID','OVERDUE')",
                java.math.BigDecimal.class, tenantId, workspaceId, clientAccountId, amount.currency());
        java.math.BigDecimal available = account.limit().subtract(account.reserved()).subtract(outstanding == null ? java.math.BigDecimal.ZERO : outstanding);
        if (available.compareTo(amount.amount()) < 0) throw new IllegalStateException("Credit limit exceeded");
        CreditReservationRow existing = jdbc.query("select id,amount,status from payments.credit_reservation where tenant_id=? and workspace_id=? and purchase_request_id=? for update",
                (rs, n) -> new CreditReservationRow(rs.getObject(1, UUID.class), null, rs.getBigDecimal(2), rs.getString(3)),
                tenantId, workspaceId, purchaseRequestId).stream().findFirst().orElse(null);
        if (existing != null && "RESERVED".equals(existing.status())) return;
        if (existing != null && !"RELEASED".equals(existing.status())) throw new IllegalStateException("Credit commitment is no longer reusable");
        if (existing == null) {
            jdbc.update("insert into payments.credit_reservation (id,tenant_id,workspace_id,credit_account_id,purchase_request_id,amount,status,idempotency_key,created_at) values (?,?,?,?,?,?,'RESERVED',?,?)",
                    UUID.randomUUID(), tenantId, workspaceId, account.id(), purchaseRequestId, amount.amount(), "purchase-request:" + purchaseRequestId, Timestamp.from(now));
        } else {
            jdbc.update("update payments.credit_reservation set credit_account_id=?,amount=?,status='RESERVED',released_at=null,created_at=? where tenant_id=? and workspace_id=? and id=?",
                    account.id(), amount.amount(), Timestamp.from(now), tenantId, workspaceId, existing.id());
        }
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure+?,version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? and reserved_exposure+?<=credit_limit-?",
                amount.amount(), Timestamp.from(now), tenantId, workspaceId, account.id(), amount.amount(), outstanding == null ? java.math.BigDecimal.ZERO : outstanding) != 1) {
            throw new IllegalStateException("Credit limit exceeded");
        }
    }

    private void releaseCredit(UUID tenantId, UUID workspaceId, UUID purchaseRequestId) {
        CreditReservationRow reservation = jdbc.query("select id,credit_account_id,amount,status from payments.credit_reservation where tenant_id=? and workspace_id=? and purchase_request_id=? for update",
                (rs, n) -> new CreditReservationRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getString(4)),
                tenantId, workspaceId, purchaseRequestId).stream().findFirst().orElse(null);
        if (reservation == null || !"RESERVED".equals(reservation.status())) return;
        if (jdbc.update("update payments.credit_account set reserved_exposure=reserved_exposure-?,version=version+1,updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and reserved_exposure>=?",
                reservation.amount(), tenantId, workspaceId, reservation.creditAccountId(), reservation.amount()) != 1) {
            throw new IllegalStateException("Credit reservation balance is inconsistent");
        }
        jdbc.update("update payments.credit_reservation set status='RELEASED',released_at=current_timestamp where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                tenantId, workspaceId, reservation.id());
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "Lifecycle transition";
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason.trim();
    }

    private record CommitmentLine(UUID requestLineId, UUID skuId, String skuCode, java.math.BigDecimal quantity,
                                  String unit, String currency) { }
    private record AmountRow(java.math.BigDecimal amount, String currency) { }
    private record CreditAccountRow(UUID id, java.math.BigDecimal limit, java.math.BigDecimal reserved) { }
    private record CreditReservationRow(UUID id, UUID creditAccountId, java.math.BigDecimal amount, String status) { }
}
