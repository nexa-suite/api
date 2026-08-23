package com.nexa.api.sales.infrastructure.commitment;

import com.nexa.api.sales.application.port.CommercialCommitmentPort;
import com.nexa.api.catalogmanagement.application.publicapi.SellableSkuQuery;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.CreditReservationCommands;
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
    private final SellableSkuQuery sellableSkus;
    private final CustomerAccountQuery customers;
    private final CreditReservationCommands creditReservations;

    public CommercialCommitmentPersistenceAdapter(
            JdbcTemplate jdbc, SellableSkuQuery sellableSkus, CustomerAccountQuery customers,
            CreditReservationCommands creditReservations) {
        this.jdbc = jdbc;
        this.sellableSkus = sellableSkus;
        this.customers = customers;
        this.creditReservations = creditReservations;
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
        jdbc.query("select l.id,l.sku_id,l.sku_code_snapshot,l.catalog_item_id,l.quantity,l.unit,l.unit_price_currency "
                        + "from sales.purchase_request_line l join sales.purchase_request r on r.id=l.purchase_request_id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? order by l.created_at,l.id",
                (rs, n) -> new CommitmentLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7)),
                tenantId, workspaceId, purchaseRequestId)
                .forEach(line -> {
                    CommitmentSku sku = commitmentSku(tenantId, workspaceId, line);
                    jdbc.update("insert into sales.commercial_commitment_line (id,tenant_id,workspace_id,commitment_id,purchase_request_line_id,sku_id,sku_code_snapshot,quantity,unit,currency,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                            UUID.randomUUID(), tenantId, workspaceId, activeCommitmentId, line.requestLineId(), sku.id(), sku.code(),
                            line.quantity(), line.unit(), line.currency(), Timestamp.from(now));
                });
        String paymentOption = jdbc.queryForObject("select payment_option from sales.purchase_request where tenant_id=? and workspace_id=? and id=?",
                String.class, tenantId, workspaceId, purchaseRequestId);
        if ("CREDIT_LINE".equalsIgnoreCase(paymentOption)) {
            AmountRow amount = amount(purchaseRequestId);
            var customer = customers.findActiveDetails(tenantId.toString(), workspaceId.toString(), clientAccountId.toString())
                    .orElseThrow(() -> new IllegalStateException("Active client account is required for credit commitment"));
            if (!amount.currency().equalsIgnoreCase(customer.creditCurrency())) {
                throw new IllegalStateException("Client credit account is not configured for purchase currency");
            }
            creditReservations.reserve(tenantId, workspaceId, clientAccountId, purchaseRequestId,
                    amount.amount(), amount.currency(), customer.creditLimit(), now);
        }
    }

    @Override
    @Transactional
    public void releaseForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, String reason) {
        jdbc.update("update sales.commercial_commitment set status='RELEASED',released_at=current_timestamp,release_reason=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and purchase_request_id=? and status='ACTIVE'",
                normalizeReason(reason), tenantId, workspaceId, purchaseRequestId);
        creditReservations.release(tenantId, workspaceId, purchaseRequestId);
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
        creditReservations.linkSalesOrder(tenantId, workspaceId, purchaseRequestId, salesOrderId);
    }

    private AmountRow amount(UUID purchaseRequestId) {
        AmountRow amount = jdbc.query("select coalesce(sum(quantity * unit_price_amount),0),max(unit_price_currency) "
                        + "from sales.purchase_request_line where purchase_request_id=?",
                (rs, n) -> new AmountRow(rs.getBigDecimal(1), rs.getString(2)), purchaseRequestId).stream()
                .findFirst().orElse(new AmountRow(java.math.BigDecimal.ZERO, null));
        if (amount.amount().signum() <= 0 || amount.currency() == null) throw new IllegalStateException("Credit commitment requires priced lines");
        return amount;
    }

    private CommitmentSku commitmentSku(UUID tenantId, UUID workspaceId, CommitmentLine line) {
        if (line.skuId() != null) return new CommitmentSku(line.skuId(), line.skuCode());
        var reference = sellableSkus.findActiveByLegacyCatalogItemId(tenantId, workspaceId, line.catalogItemId())
                .orElseThrow(() -> new IllegalStateException("Purchase request line is not mapped to an active sellable SKU"));
        return new CommitmentSku(reference.skuId(), reference.skuCode());
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "Lifecycle transition";
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason.trim();
    }

    private record CommitmentLine(UUID requestLineId, UUID skuId, String skuCode, String catalogItemId,
                                  java.math.BigDecimal quantity,
                                  String unit, String currency) { }
    private record CommitmentSku(UUID id, String code) { }
    private record AmountRow(java.math.BigDecimal amount, String currency) { }
}
