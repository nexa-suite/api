package com.nexa.api.salescommitment.infrastructure.commitment;

import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditReservationCommands;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import com.nexa.api.shared.infrastructure.events.CanonicalOutbox;
import com.nexa.api.salescommitment.application.exception.SalesIdempotencyPayloadConflictException;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable V1 commitment projection kept behind an application port. */
@Repository
@Profile("!test")
public class CommercialCommitmentPersistenceAdapter implements CommercialCommitmentPort {
    private final JdbcTemplate jdbc;
    private final SellableSkuQuery sellableSkus;
    private final CustomerAccountQuery customers;
    private final CreditReservationCommands creditReservations;
    private final InventoryBackingCommands inventoryBacking;
    private final PaymentConfirmationQuery paymentConfirmations;
    private final ReceivableCommands receivables;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CommercialCommitmentPersistenceAdapter(
            JdbcTemplate jdbc, SellableSkuQuery sellableSkus, CustomerAccountQuery customers,
            CreditReservationCommands creditReservations, InventoryBackingCommands inventoryBacking,
            PaymentConfirmationQuery paymentConfirmations, ReceivableCommands receivables, Clock clock) {
        this.jdbc = jdbc;
        this.sellableSkus = sellableSkus;
        this.customers = customers;
        this.creditReservations = creditReservations;
        this.inventoryBacking = inventoryBacking;
        this.paymentConfirmations = paymentConfirmations;
        this.receivables = receivables;
        this.clock = clock;
    }

    public CommercialCommitmentPersistenceAdapter(
            JdbcTemplate jdbc, SellableSkuQuery sellableSkus, CustomerAccountQuery customers,
            CreditReservationCommands creditReservations, InventoryBackingCommands inventoryBacking,
            PaymentConfirmationQuery paymentConfirmations, Clock clock) {
        this(jdbc, sellableSkus, customers, creditReservations, inventoryBacking, paymentConfirmations, null, clock);
    }

    public CommercialCommitmentPersistenceAdapter(
            JdbcTemplate jdbc, SellableSkuQuery sellableSkus, CustomerAccountQuery customers,
            CreditReservationCommands creditReservations, InventoryBackingCommands inventoryBacking, Clock clock) {
        this(jdbc, sellableSkus, customers, creditReservations, inventoryBacking, null, null, clock);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void activateForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId) {
        Instant now = clock.instant();
        PurchaseRequestRow request = jdbc.query(
                "select client_account_id,status,payment_option from sales.purchase_request where tenant_id=? and workspace_id=? and id=? for update",
                (org.springframework.jdbc.core.ResultSetExtractor<PurchaseRequestRow>) rs -> rs.next()
                        ? new PurchaseRequestRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)) : null,
                tenantId, workspaceId, purchaseRequestId);
        if (request == null) throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        if (!"SUBMITTED".equals(request.status())) throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        UUID clientAccountId = request.clientAccountId();
        CommitmentRow existing = jdbc.query(
                "select id,status from sales.commercial_commitment where tenant_id=? and workspace_id=? and purchase_request_id=? for update",
                (org.springframework.jdbc.core.ResultSetExtractor<CommitmentRow>) rs -> rs.next()
                        ? new CommitmentRow(rs.getObject(1, UUID.class), rs.getString(2)) : null,
                tenantId, workspaceId, purchaseRequestId);
        UUID commitmentId = existing == null ? UUID.randomUUID() : existing.id();
        if (existing == null) {
            jdbc.update("insert into sales.commercial_commitment (id,tenant_id,workspace_id,purchase_request_id,client_account_id,status,created_at,updated_at,version) values (?,?,?,?,?,'ACTIVE',?,?,0)",
                    commitmentId, tenantId, workspaceId, purchaseRequestId, clientAccountId, Timestamp.from(now), Timestamp.from(now));
        } else {
            if ("CONVERTED".equals(existing.status())) throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
            jdbc.update("update sales.commercial_commitment set status='ACTIVE',released_at=null,release_reason=null,converted_at=null,sales_order_id=null,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status <> 'CONVERTED'",
                    Timestamp.from(now), tenantId, workspaceId, commitmentId);
        }

        jdbc.update("delete from sales.commercial_commitment_line where tenant_id=? and workspace_id=? and commitment_id=?",
                tenantId, workspaceId, commitmentId);
        UUID activeCommitmentId = commitmentId;
        List<CommitmentLine> lines = jdbc.query("select l.id,l.sku_id,l.sku_code_snapshot,l.catalog_item_id,l.quantity,l.unit,l.unit_price_amount,l.unit_price_currency "
                        + "from sales.purchase_request_line l join sales.purchase_request r on r.id=l.purchase_request_id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.id=? order by l.created_at,l.id",
                (rs, n) -> new CommitmentLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8)),
                tenantId, workspaceId, purchaseRequestId);
        List<InventoryBackingCommands.RequestedLine> backingLines = new ArrayList<>();
        for (CommitmentLine line : lines) {
                    if (line.price() == null || line.currency() == null || line.currency().isBlank()) {
                        throw new CommercialBusinessException("COMMERCIAL_POLICY_CHANGED");
                    }
                    CommitmentSku sku = commitmentSku(tenantId, workspaceId, line);
                    jdbc.update("insert into sales.commercial_commitment_line (id,tenant_id,workspace_id,commitment_id,purchase_request_line_id,sku_id,sku_code_snapshot,quantity,unit,currency,unit_price_amount,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                            UUID.randomUUID(), tenantId, workspaceId, activeCommitmentId, line.requestLineId(), sku.id(), sku.code(),
                            line.quantity(), line.unit(), line.currency(), line.price(), Timestamp.from(now));
                    backingLines.add(new InventoryBackingCommands.RequestedLine(sku.id(), line.catalogItemId(), line.quantity(), line.unit()));
        }
        inventoryBacking.establish(tenantId, workspaceId, activeCommitmentId, backingLines, now);
        if ("CREDIT_LINE".equalsIgnoreCase(request.paymentOption())) {
            AmountRow amount = amount(purchaseRequestId);
            customers.findReference(tenantId.toString(), workspaceId.toString(), clientAccountId.toString())
                    .filter(reference -> "ACTIVE".equalsIgnoreCase(reference.status()))
                    .orElseThrow(() -> new IllegalStateException("Active client account is required for credit commitment"));
            try {
                creditReservations.reserveForCommitment(tenantId, workspaceId, clientAccountId, activeCommitmentId,
                        purchaseRequestId, null, amount.amount(), amount.currency(), now);
            } catch (RuntimeException exception) {
                if (creditFailure(exception)) {
                    throw new CommercialBusinessException("INSUFFICIENT_CREDIT");
                }
                throw exception;
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, String reason) {
        Instant now = clock.instant();
        UUID commitmentId = jdbc.query("select id from sales.commercial_commitment where tenant_id=? and workspace_id=? and purchase_request_id=? for update",
                (org.springframework.jdbc.core.ResultSetExtractor<UUID>) rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenantId, workspaceId, purchaseRequestId);
        if (commitmentId == null) return;
        inventoryBacking.release(tenantId, workspaceId, commitmentId, reason, now);
        creditReservations.releaseForCommitment(tenantId, workspaceId, commitmentId, purchaseRequestId);
        String terminalStatus = switch (normalizeReason(reason)) {
            case "EXPIRED" -> "EXPIRED";
            case "WITHDRAWN" -> "WITHDRAWN";
            default -> "RELEASED";
        };
        jdbc.update("update sales.commercial_commitment set status=?,released_at=?,release_reason=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                terminalStatus, Timestamp.from(now), normalizeReason(reason), Timestamp.from(now), tenantId, workspaceId, commitmentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void convertForSalesOrder(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, UUID salesOrderId) {
        int changed = jdbc.update("update sales.commercial_commitment set status='CONVERTED',sales_order_id=?,converted_at=current_timestamp,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and purchase_request_id=? and status='ACTIVE'",
                salesOrderId, tenantId, workspaceId, purchaseRequestId);
        if (changed == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.commercial_commitment where tenant_id=? and workspace_id=? and purchase_request_id=? and status='CONVERTED' and sales_order_id=?)", Boolean.class,
                tenantId, workspaceId, purchaseRequestId, salesOrderId))) {
            throw new IllegalStateException("Active commercial commitment is required before Sales Order conversion");
        }
        creditReservations.linkSalesOrderForCommitment(tenantId, workspaceId, null, purchaseRequestId, salesOrderId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId, String reason) {
        Instant now = clock.instant();
        SalesOrderCommitmentRow commitment = jdbc.query(
                "select c.id,c.purchase_request_id,c.status from sales.sales_order o join sales.commercial_commitment c on c.tenant_id=o.tenant_id and c.workspace_id=o.workspace_id and c.id=o.commercial_commitment_id where o.tenant_id=? and o.workspace_id=? and o.id=? for update of c",
                (org.springframework.jdbc.core.ResultSetExtractor<SalesOrderCommitmentRow>) rs -> rs.next()
                        ? new SalesOrderCommitmentRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)) : null,
                tenantId, workspaceId, salesOrderId);
        if (commitment == null) return;
        inventoryBacking.release(tenantId, workspaceId, commitment.id(), reason, now);
        creditReservations.releaseForCommitment(tenantId, workspaceId, commitment.id(), commitment.purchaseRequestId());
        String releaseReason = normalizeReason(reason);
        if ("ACTIVE".equals(commitment.status())) {
            jdbc.update("update sales.commercial_commitment set status='RELEASED',released_at=?,release_reason=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                    Timestamp.from(now), releaseReason, Timestamp.from(now), tenantId, workspaceId, commitment.id());
        } else if ("CONVERTED".equals(commitment.status())) {
            jdbc.update("update sales.commercial_commitment set released_at=coalesce(released_at,?),release_reason=coalesce(release_reason,?),updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='CONVERTED'",
                    Timestamp.from(now), releaseReason, Timestamp.from(now), tenantId, workspaceId, commitment.id());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmDirectOrder(UUID tenantId, UUID workspaceId, UUID commitmentId, UUID salesOrderId) {
        int changed = jdbc.update("update sales.commercial_commitment set status='CONVERTED',sales_order_id=?,converted_at=current_timestamp,updated_at=current_timestamp,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and origin_type='DIRECT_ORDER' and status='ACTIVE'",
                salesOrderId, tenantId, workspaceId, commitmentId);
        if (changed == 0 && !Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from sales.commercial_commitment where tenant_id=? and workspace_id=? and id=? and origin_type='DIRECT_ORDER' and status='CONVERTED' and sales_order_id=?)",
                Boolean.class, tenantId, workspaceId, commitmentId, salesOrderId))) {
            throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DirectOrderResult establishDirectOrder(DirectOrderCommand command) {
        if (command == null || command.lines() == null || command.lines().isEmpty()) {
            throw new CommercialBusinessException("VALIDATION_ERROR");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 160) {
            throw new CommercialBusinessException("IDEMPOTENCY_KEY_REQUIRED");
        }
        UUID tenant = command.tenantId(), workspace = command.workspaceId(), actor = command.actorMembershipId();
        Instant now = command.now() == null ? clock.instant() : command.now();
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|direct-order|" + actor + "|" + command.idempotencyKey());
        IdempotentOrder prior = jdbc.query(
                "select resource_id,request_hash from sales.idempotency_record where tenant_id=? and workspace_id=? and actor_membership_id=? and operation='direct-order' and idempotency_key=?",
                (org.springframework.jdbc.core.ResultSetExtractor<IdempotentOrder>) rs -> rs.next() ? new IdempotentOrder(rs.getObject(1, UUID.class), rs.getString(2)) : null,
                tenant, workspace, actor, command.idempotencyKey());
        if (prior != null) {
            if (prior.hash() != null && !prior.hash().isBlank() && !prior.hash().equalsIgnoreCase(command.requestHash())) {
                throw new SalesIdempotencyPayloadConflictException();
            }
            return result(tenant, workspace, prior.orderId());
        }

        customers.findReference(tenant.toString(), workspace.toString(), command.clientAccountId().toString())
                .filter(reference -> "ACTIVE".equalsIgnoreCase(reference.status()))
                .orElseThrow(() -> new CommercialBusinessException("CLIENT_ACCOUNT_NOT_FOUND"));
        List<ResolvedDirectLine> lines = new ArrayList<>();
        String currency = null;
        for (DirectOrderLine requested : command.lines()) {
            if (requested == null || requested.catalogItemId() == null || requested.catalogItemId().isBlank()
                    || requested.quantity() == null || requested.quantity().signum() <= 0) {
                throw new CommercialBusinessException("VALIDATION_ERROR");
            }
            var sku = sellableSkus.findActiveByLegacyCatalogItemId(tenant, workspace, requested.catalogItemId().trim())
                    .orElseThrow(() -> new CommercialBusinessException("COMMERCIAL_POLICY_CHANGED"));
            String lineCurrency = sku.currency().toUpperCase(java.util.Locale.ROOT);
            if (currency == null) currency = lineCurrency;
            if (!currency.equals(lineCurrency)) throw new CommercialBusinessException("COMMERCIAL_POLICY_CHANGED");
            String unit = requested.unit() == null || requested.unit().isBlank() ? sku.unitOfMeasure() : requested.unit().trim();
            lines.add(new ResolvedDirectLine(requested.catalogItemId().trim(), sku, requested.quantity(), unit));
        }
        if (lines.stream().map(line -> line.sku().skuId()).distinct().count() != lines.size()) {
            throw new CommercialBusinessException("VALIDATION_ERROR");
        }
        BigDecimal total = lines.stream().map(line -> line.quantity().multiply(line.sku().price())).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID commitmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String orderNumber = nextOrderNumber(tenant, workspace, now);
        jdbc.update("insert into sales.commercial_commitment(id,tenant_id,workspace_id,purchase_request_id,client_account_id,status,created_at,updated_at,version,origin_type) values (?,?,?,?,?,'ACTIVE',?,?,0,'DIRECT_ORDER')",
                commitmentId, tenant, workspace, null, command.clientAccountId(), Timestamp.from(now), Timestamp.from(now));
        List<InventoryBackingCommands.RequestedLine> backingLines = new ArrayList<>();
        for (ResolvedDirectLine line : lines) {
            jdbc.update("insert into sales.commercial_commitment_line(id,tenant_id,workspace_id,commitment_id,purchase_request_line_id,sku_id,sku_code_snapshot,quantity,unit,currency,unit_price_amount,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), tenant, workspace, commitmentId, null, line.sku().skuId(), line.sku().skuCode(),
                    line.quantity(), line.unit(), currency, line.sku().price(), Timestamp.from(now));
            backingLines.add(new InventoryBackingCommands.RequestedLine(line.sku().skuId(), line.catalogItemId(), line.quantity(), line.unit()));
        }
        jdbc.update("insert into sales.sales_order(id,tenant_id,workspace_id,number,client_account_id,created_by_membership_id,buyer_membership_id,source_purchase_request_id,order_source,priority,requested_delivery_date,delivery_snapshot,payment_option,notes,currency,total_amount,status,created_at,updated_at,version,commercial_commitment_id,origin_type) values (?,?,?,?,?,?,?,?, 'DIRECT_ORDER',?,?,?,?,?,?,?,'PENDING',?,?,0,?,'DIRECT_ORDER')",
                orderId, tenant, workspace, orderNumber, command.clientAccountId(), actor, command.buyerMembershipId(), null,
                command.priority(), command.requestedDeliveryDate(), command.deliverySnapshot(), command.paymentOption(), command.notes(), currency,
                total, Timestamp.from(now), Timestamp.from(now), commitmentId);
        for (ResolvedDirectLine line : lines) {
            var sku = line.sku();
            BigDecimal subtotal = line.quantity().multiply(sku.price());
            jdbc.update("insert into sales.sales_order_line(id,sales_order_id,catalog_item_id,sku_id,product_family_id,sku_code_snapshot,product_family_code_snapshot,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,line_subtotal,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), orderId, line.catalogItemId(), sku.skuId(), sku.familyId(), sku.skuCode(), sku.familyCode(),
                    sku.familyName(), sku.presentation(), line.quantity(), line.unit(), sku.price(), currency, subtotal, Timestamp.from(now));
        }
        inventoryBacking.establish(tenant, workspace, commitmentId, backingLines, now);
        if ("CREDIT_LINE".equalsIgnoreCase(command.paymentOption())) {
            try {
                creditReservations.reserveForCommitment(tenant, workspace, command.clientAccountId(), commitmentId, null, orderId,
                        total, currency, now);
            } catch (RuntimeException exception) {
                if (creditFailure(exception)) {
                    throw new CommercialBusinessException("INSUFFICIENT_CREDIT");
                }
                throw exception;
            }
        }
        if ("PREPAID".equalsIgnoreCase(command.paymentOption())) {
            jdbc.update("insert into sales.sales_order_event(id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,to_status,reason,occurred_at) values (?,?,?,?,?,'DIRECT_ORDER_PAYMENT_PENDING','PENDING','Payment required before confirmation',?)",
                    UUID.randomUUID(), orderId, tenant, workspace, actor, Timestamp.from(now));
        }
        DirectOrderResult pending = new DirectOrderResult(commitmentId, orderId, orderNumber, 0);
        if ("PREPAID".equalsIgnoreCase(command.paymentOption())) {
            saveDirectOrderIdempotency(command, pending, now);
            return pending;
        }
        DirectOrderResult confirmed = confirmDirectOrder(command, pending, now);
        saveDirectOrderIdempotency(command, confirmed, now);
        return confirmed;
    }

    private DirectOrderResult confirmDirectOrder(DirectOrderCommand command, DirectOrderResult pending, Instant now) {
        UUID tenant = command.tenantId(), workspace = command.workspaceId(), actor = command.actorMembershipId();
        String currentStatus = jdbc.query("select status from sales.sales_order where tenant_id=? and workspace_id=? and id=? for update",
                (org.springframework.jdbc.core.ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null,
                tenant, workspace, pending.salesOrderId());
        if (currentStatus == null) throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        if ("CONFIRMED".equals(currentStatus)) return result(tenant, workspace, pending.salesOrderId());
        if (!"PENDING".equals(currentStatus)) throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        if ("PREPAID".equalsIgnoreCase(command.paymentOption())
                && (paymentConfirmations == null || !paymentConfirmations.isConfirmed(tenant, workspace, pending.salesOrderId()))) {
            throw new CommercialBusinessException("PAYMENT_REQUIRED");
        }
        int orderChanged = jdbc.update("update sales.sales_order set status='CONFIRMED',confirmed_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='PENDING'",
                Timestamp.from(now), Timestamp.from(now), tenant, workspace, pending.salesOrderId());
        if (orderChanged != 1) throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        if ("CREDIT_LINE".equalsIgnoreCase(command.paymentOption()) && receivables != null) {
            receivables.postForSalesOrder(tenant, workspace, pending.salesOrderId(), command.clientAccountId(),
                    jdbc.queryForObject("select total_amount from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                            BigDecimal.class, tenant, workspace, pending.salesOrderId()),
                    jdbc.queryForObject("select currency from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                            String.class, tenant, workspace, pending.salesOrderId()), now);
        }
        if (jdbc.update("update sales.commercial_commitment set status='CONVERTED',sales_order_id=?,converted_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                pending.salesOrderId(), Timestamp.from(now), Timestamp.from(now), tenant, workspace, pending.commitmentId()) != 1) {
            throw new CommercialBusinessException("PURCHASE_REQUEST_NOT_CONFIRMABLE");
        }
        jdbc.update("insert into sales.sales_order_event(id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,to_status,reason,occurred_at) values (?,?,?,?,?,'DIRECT_ORDER_CONFIRMED','CONFIRMED','Direct Order',?)",
                UUID.randomUUID(), pending.salesOrderId(), tenant, workspace, actor, Timestamp.from(now));
        CanonicalOutbox.append(jdbc, "SALES_ORDER_CONFIRMED", "SalesOrder", pending.salesOrderId(), tenant, workspace,
                now, "direct-order-" + pending.salesOrderId(), null, "1.0", "confirmed", Map.of("salesOrderId", pending.salesOrderId(), "salesOrderVersion", 1, "originType", "DIRECT_ORDER"));
        return new DirectOrderResult(pending.commitmentId(), pending.salesOrderId(), pending.orderNumber(), 1);
    }

    private void saveDirectOrderIdempotency(DirectOrderCommand command, DirectOrderResult result, Instant now) {
        jdbc.update("insert into sales.idempotency_record(id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,request_hash,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), command.tenantId(), command.workspaceId(), command.actorMembershipId(), "direct-order",
                command.idempotencyKey(), result.salesOrderId(), result.version(), command.requestHash(), Timestamp.from(now));
    }

    private DirectOrderResult result(UUID tenant, UUID workspace, UUID orderId) {
        return jdbc.query("select commercial_commitment_id,number,version from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                (org.springframework.jdbc.core.ResultSetExtractor<DirectOrderResult>) rs -> rs.next() ? new DirectOrderResult(rs.getObject(1, UUID.class), orderId, rs.getString(2), rs.getLong(3)) : null,
                tenant, workspace, orderId);
    }

    private String nextOrderNumber(UUID tenant, UUID workspace, Instant now) {
        int year = java.time.Year.of(java.time.ZonedDateTime.ofInstant(now, java.time.ZoneOffset.UTC).getYear()).getValue();
        jdbc.update("insert into sales.sales_order_sequence(tenant_id,workspace_id,order_year,next_value) values (?,?,?,1) on conflict do nothing", tenant, workspace, year);
        Long next = jdbc.queryForObject("select next_value from sales.sales_order_sequence where tenant_id=? and workspace_id=? and order_year=? for update", Long.class, tenant, workspace, year);
        jdbc.update("update sales.sales_order_sequence set next_value=? where tenant_id=? and workspace_id=? and order_year=?", next + 1, tenant, workspace, year);
        return String.format("SO-%04d-%06d", year, next);
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

    private static boolean creditFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.toLowerCase(java.util.Locale.ROOT).contains("credit limit exceeded")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("client credit account")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("credit commitment"))) return true;
        }
        return false;
    }

    private record CommitmentLine(UUID requestLineId, UUID skuId, String skuCode, String catalogItemId,
                                  BigDecimal quantity, String unit, BigDecimal price, String currency) { }
    private record PurchaseRequestRow(UUID clientAccountId, String status, String paymentOption) { }
    private record CommitmentRow(UUID id, String status) { }
    private record SalesOrderCommitmentRow(UUID id, UUID purchaseRequestId, String status) { }
    private record CommitmentSku(UUID id, String code) { }
    private record AmountRow(java.math.BigDecimal amount, String currency) { }
    private record IdempotentOrder(UUID orderId, String hash) { }
    private record ResolvedDirectLine(String catalogItemId, SellableSkuQuery.SellableSkuReference sku,
                                      BigDecimal quantity, String unit) { }
}
