package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Synchronous BC-08 wallet persistence explicitly bound to one caller-owned JDBC session. */
public class JdbcBuyerWalletReservationCommands implements BuyerWalletReservationCommands {
    private static final String CURRENCY = "PEN";

    private final JdbcTemplate jdbc;
    private final CustomerAccountQuery customerAccounts;

    public JdbcBuyerWalletReservationCommands(JdbcTemplate jdbc, CustomerAccountQuery customerAccounts) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
    }

    @Override
    public Reservation reserveForSalesOrder(CurrentAccessContext context, ReserveCommand command) {
        Objects.requireNonNull(command, "Reserve command is required");
        requireCallerTransaction();
        WalletScope scope = requireCurrentScope(context, PermissionKey.SALES_PURCHASE_REQUEST_REVIEW);
        return reserveWithinScope(scope, command);
    }

    @Override
    public Reservation reserveForApprovedPurchaseRequestConversion(CurrentAccessContext context,
            ApprovedPurchaseRequestReserveCommand command) {
        Objects.requireNonNull(command, "Approved Purchase Request reservation command is required");
        requireCallerTransaction();
        WalletScope scope = requireApprovedPurchaseRequestConversionScope(context);
        if (!"APPROVED".equals(command.purchaseRequestStatus()) || !"WALLET".equals(command.paymentOption())) {
            throw new IllegalStateException("Approved Purchase Request wallet consent is required");
        }
        ReserveCommand reservation = command.reservation();
        return reserveWithinScope(scope, reservation);
    }

    private Reservation reserveWithinScope(WalletScope scope, ReserveCommand command) {
        requireActiveBuyerRelationship(scope, command.buyerMembershipId(), command.buyerAccountId());
        UUID buyerIdentityId = command.buyerIdentityId();
        AccountRow account = lockAccount(scope.tenantId(), scope.workspaceId(), buyerIdentityId);

        ReservationEventRow replay = eventByKey(scope.tenantId(), scope.workspaceId(), buyerIdentityId,
                command.idempotencyKey());
        if (replay != null) {
            if (!"RESERVED".equals(replay.eventType()) || !sameMoney(replay.amount(), command.amountPEN())) {
                throw new IllegalStateException("Buyer wallet idempotency key payload conflict");
            }
            ReservationRow reservation = reservationById(scope.tenantId(), scope.workspaceId(), replay.reservationId(), true);
            if (!reservation.salesOrderId().equals(command.salesOrderId())
                    || !reservation.buyerIdentityId().equals(buyerIdentityId)) {
                throw new IllegalStateException("Buyer wallet idempotency key payload conflict");
            }
            return result(reservation);
        }
        if (ledgerByKey(scope.tenantId(), scope.workspaceId(), buyerIdentityId, command.idempotencyKey()) != null) {
            throw new IllegalStateException("Buyer wallet idempotency key was used by another operation");
        }

        ReservationRow priorOrderReservation = reservationByOrder(scope.tenantId(), scope.workspaceId(),
                buyerIdentityId, command.salesOrderId(), true);
        if (priorOrderReservation != null) {
            throw new IllegalStateException("Sales Order already has a Buyer wallet reservation");
        }
        if (account.available().compareTo(command.amountPEN()) < 0) {
            throw new IllegalStateException("Buyer wallet has insufficient available funds");
        }

        UUID reservationId = UUID.randomUUID();
        jdbc.update("insert into payments.buyer_wallet_reservation "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,sales_order_id,amount,"
                        + "reserve_idempotency_key,created_at) values (?,?,?,?,?,?,?,?,?)",
                reservationId, scope.tenantId(), scope.workspaceId(), buyerIdentityId, CURRENCY,
                command.salesOrderId(), command.amountPEN(), command.idempotencyKey(), timestamp(command.occurredAt()));
        if (jdbc.update("update payments.buyer_wallet_account set reserved_balance=reserved_balance+?, "
                        + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? "
                        + "and posted_balance-reserved_balance>=?",
                command.amountPEN(), timestamp(command.occurredAt()), scope.tenantId(), scope.workspaceId(),
                account.id(), command.amountPEN()) != 1) {
            throw new IllegalStateException("Buyer wallet available balance changed");
        }
        appendReservationEvent(scope.tenantId(), scope.workspaceId(), buyerIdentityId, reservationId,
                "RESERVED", command.amountPEN(), command.idempotencyKey(), command.occurredAt());
        return new Reservation(reservationId, command.salesOrderId(), command.amountPEN(), ReservationStatus.RESERVED);
    }

    @Override
    public Reservation consumeForSalesOrder(CurrentAccessContext context, TransitionCommand command) {
        return transition(context, Objects.requireNonNull(command, "Transition command is required"), true,
                Permission.SALES_WRITE);
    }

    @Override
    public Reservation consumeForPurchaseRequestConversion(CurrentAccessContext context, TransitionCommand command) {
        return transition(context, Objects.requireNonNull(command, "Transition command is required"), true,
                PermissionKey.SALES_PURCHASE_REQUEST_REVIEW);
    }

    @Override
    public Reservation consumeForApprovedPurchaseRequestConversion(CurrentAccessContext context,
            TransitionCommand command) {
        Objects.requireNonNull(command, "Transition command is required");
        requireCallerTransaction();
        return transitionWithinScope(context, command, true,
                requireApprovedPurchaseRequestConversionScope(context));
    }

    @Override
    public Reservation releaseForSalesOrder(CurrentAccessContext context, TransitionCommand command) {
        return transition(context, Objects.requireNonNull(command, "Transition command is required"), false,
                Permission.SALES_WRITE);
    }

    @Override
    public Refund refundForSalesOrder(CurrentAccessContext context, RefundCommand command) {
        Objects.requireNonNull(command, "Refund command is required");
        requireCallerTransaction();
        WalletScope scope = requireCurrentScope(context, PermissionKey.PAYMENT_RECONCILE);
        ReservationRow initial = reservationByOrder(scope.tenantId(), scope.workspaceId(), command.salesOrderId(), false);
        if (initial == null) throw new IllegalStateException("Buyer wallet reservation was not found");
        AccountRow account = lockAccount(scope.tenantId(), scope.workspaceId(), initial.buyerIdentityId());
        ReservationRow reservation = reservationById(scope.tenantId(), scope.workspaceId(), initial.id(), true);
        requireSameReservation(initial, reservation);

        LedgerRow byKey = ledgerByKey(scope.tenantId(), scope.workspaceId(), reservation.buyerIdentityId(),
                command.idempotencyKey());
        if (byKey != null) return replayRefund(byKey, reservation, command);
        if (eventByKey(scope.tenantId(), scope.workspaceId(), reservation.buyerIdentityId(),
                command.idempotencyKey()) != null) {
            throw new IllegalStateException("Buyer wallet idempotency key was used by another operation");
        }
        LedgerRow byRefund = ledgerBySource(scope.tenantId(), scope.workspaceId(),
                reservation.buyerIdentityId(), "REFUND", command.refundId());
        if (byRefund != null) return replayRefund(byRefund, reservation, command);
        if (reservation.status() != ReservationStatus.CONSUMED) {
            throw new IllegalStateException("Only consumed Buyer wallet funds can be refunded");
        }

        BigDecimal consumed = sumLedgerAmount(scope.tenantId(), scope.workspaceId(), reservation.id(),
                "ORDER_CONSUMPTION").negate();
        BigDecimal alreadyRefunded = sumLedgerAmount(scope.tenantId(), scope.workspaceId(), reservation.id(), "REFUND");
        if (consumed.signum() <= 0 || alreadyRefunded.add(command.amountPEN()).compareTo(consumed) > 0) {
            throw new IllegalStateException("Buyer wallet refund exceeds consumed funds");
        }
        if (jdbc.update("update payments.buyer_wallet_account set posted_balance=posted_balance+?, "
                        + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=?",
                command.amountPEN(), timestamp(command.occurredAt()), scope.tenantId(), scope.workspaceId(),
                account.id()) != 1) {
            throw new IllegalStateException("Buyer wallet account is unavailable");
        }
        UUID entryId = UUID.randomUUID();
        jdbc.update("insert into payments.buyer_wallet_ledger_entry "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,reservation_id,entry_type,"
                        + "amount_delta,source_id,idempotency_key,occurred_at) values (?,?,?,?,? ,?,'REFUND',?,?,?,?)",
                entryId, scope.tenantId(), scope.workspaceId(), reservation.buyerIdentityId(), CURRENCY,
                reservation.id(), command.amountPEN(), command.refundId(), command.idempotencyKey(),
                timestamp(command.occurredAt()));
        return new Refund(entryId, command.refundId(), command.amountPEN());
    }

    private Reservation transition(CurrentAccessContext context, TransitionCommand command, boolean consume,
                                   Permission permission) {
        requireCallerTransaction();
        return transitionWithinScope(context, command, consume, requireCurrentScope(context, permission));
    }

    private Reservation transition(CurrentAccessContext context, TransitionCommand command, boolean consume,
                                   PermissionKey permission) {
        requireCallerTransaction();
        return transitionWithinScope(context, command, consume, requireCurrentScope(context, permission));
    }

    private Reservation transitionWithinScope(CurrentAccessContext context, TransitionCommand command,
                                              boolean consume, WalletScope scope) {
        ReservationRow initial = reservationByOrder(scope.tenantId(), scope.workspaceId(), command.salesOrderId(), false);
        if (initial == null) throw new IllegalStateException("Buyer wallet reservation was not found");
        AccountRow account = lockAccount(scope.tenantId(), scope.workspaceId(), initial.buyerIdentityId());
        ReservationRow reservation = reservationById(scope.tenantId(), scope.workspaceId(), initial.id(), true);
        requireSameReservation(initial, reservation);
        String eventType = consume ? "CONSUMED" : "RELEASED";

        ReservationEventRow replay = eventByKey(scope.tenantId(), scope.workspaceId(),
                reservation.buyerIdentityId(), command.idempotencyKey());
        if (replay != null) {
            if (!eventType.equals(replay.eventType()) || !replay.reservationId().equals(reservation.id())
                    || !sameMoney(replay.amount(), reservation.amount())) {
                throw new IllegalStateException("Buyer wallet idempotency key payload conflict");
            }
            return result(reservation);
        }
        if (ledgerByKey(scope.tenantId(), scope.workspaceId(), reservation.buyerIdentityId(),
                command.idempotencyKey()) != null) {
            throw new IllegalStateException("Buyer wallet idempotency key was used by another operation");
        }
        if (reservation.status() == ReservationStatus.CONSUMED && !consume) {
            return result(reservation);
        }
        if (reservation.status() == ReservationStatus.RELEASED && !consume) {
            return result(reservation);
        }
        if (reservation.status() != ReservationStatus.RESERVED) {
            throw new IllegalStateException("Buyer wallet reservation is already terminal");
        }

        int accountUpdated;
        if (consume) {
            accountUpdated = jdbc.update("update payments.buyer_wallet_account set "
                            + "posted_balance=posted_balance-?,reserved_balance=reserved_balance-?, "
                            + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? "
                            + "and posted_balance>=? and reserved_balance>=?",
                    reservation.amount(), reservation.amount(), timestamp(command.occurredAt()), scope.tenantId(),
                    scope.workspaceId(), account.id(), reservation.amount(), reservation.amount());
        } else {
            accountUpdated = jdbc.update("update payments.buyer_wallet_account set reserved_balance=reserved_balance-?, "
                            + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=? "
                            + "and reserved_balance>=?",
                    reservation.amount(), timestamp(command.occurredAt()), scope.tenantId(), scope.workspaceId(),
                    account.id(), reservation.amount());
        }
        if (accountUpdated != 1) throw new IllegalStateException("Buyer wallet balance is inconsistent");

        String timestampColumn = consume ? "consumed_at" : "released_at";
        if (jdbc.update("update payments.buyer_wallet_reservation set status=?,updated_at=?," + timestampColumn
                        + "=? where tenant_id=? and workspace_id=? and id=? and status='RESERVED'",
                eventType, timestamp(command.occurredAt()), timestamp(command.occurredAt()), scope.tenantId(),
                scope.workspaceId(), reservation.id()) != 1) {
            throw new IllegalStateException("Buyer wallet reservation changed concurrently");
        }
        if (consume) {
            jdbc.update("insert into payments.buyer_wallet_ledger_entry "
                            + "(id,tenant_id,workspace_id,buyer_identity_id,currency,reservation_id,entry_type,"
                            + "amount_delta,source_id,idempotency_key,occurred_at) "
                            + "values (?,?,?,?,?,?,'ORDER_CONSUMPTION',?,?,?,?)",
                    UUID.randomUUID(), scope.tenantId(), scope.workspaceId(), reservation.buyerIdentityId(),
                    CURRENCY, reservation.id(), reservation.amount().negate(), reservation.salesOrderId(),
                    command.idempotencyKey(), timestamp(command.occurredAt()));
        }
        appendReservationEvent(scope.tenantId(), scope.workspaceId(), reservation.buyerIdentityId(),
                reservation.id(), eventType, reservation.amount(), command.idempotencyKey(), command.occurredAt());
        return new Reservation(reservation.id(), reservation.salesOrderId(), reservation.amount(),
                consume ? ReservationStatus.CONSUMED : ReservationStatus.RELEASED);
    }

    private WalletScope requireCurrentScope(CurrentAccessContext context, Permission permission) {
        Objects.requireNonNull(context, "Verified current access context is required");
        context.requirePermission(permission);
        return requireTenantScope(context);
    }

    private WalletScope requireCurrentScope(CurrentAccessContext context, PermissionKey permission) {
        Objects.requireNonNull(context, "Verified current access context is required");
        context.requirePermission(permission);
        return requireTenantScope(context);
    }

    private WalletScope requireApprovedPurchaseRequestConversionScope(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified current access context is required");
        if (context.surface() != Surface.PLATFORM || !context.hasRoleCode("system_workflow")) {
            throw new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation(
                    "Verified SYSTEM_WORKFLOW scope is required for approved Purchase Request wallet conversion");
        }
        context.requirePermission(PermissionKey.SALES_PURCHASE_REQUEST_READ);
        context.requirePermission(PermissionKey.SALES_ORDER_CREATE_MANUAL);
        return requireTenantScope(context);
    }

    private WalletScope requireTenantScope(CurrentAccessContext context) {
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        if (scope == null || !tenantId.equals(scope.tenantId()) || !workspaceId.equals(scope.workspaceId())) {
            throw new IllegalStateException("Buyer wallet command requires the matching verified Tenant and Workspace scope");
        }
        return new WalletScope(tenantId, workspaceId);
    }

    private void requireActiveBuyerRelationship(WalletScope scope, UUID membershipId, UUID accountId) {
        CustomerAccountReference account = customerAccounts.findBuyerReference(
                        scope.tenantId().toString(), scope.workspaceId().toString(), membershipId.toString())
                .filter(CustomerAccountReference::active)
                .orElseThrow(() -> new IllegalStateException("Active Buyer account relationship is required"));
        if (account.id() == null || account.id().isBlank() || !accountId.toString().equals(account.id())) {
            throw new IllegalStateException("Active Buyer account relationship is required");
        }
    }

    private void requireCallerTransaction() {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException(
                    "Buyer wallet command requires the caller's active write transaction on its bound Tenant database");
        }
    }

    private AccountRow lockAccount(UUID tenantId, UUID workspaceId, UUID buyerIdentityId) {
        return jdbc.query("select id,posted_balance,reserved_balance from payments.buyer_wallet_account "
                        + "where tenant_id=? and workspace_id=? and buyer_identity_id=? and currency='PEN' for update",
                (rs, ignored) -> new AccountRow(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                tenantId, workspaceId, buyerIdentityId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Buyer wallet account is unavailable"));
    }

    private ReservationRow reservationByOrder(UUID tenantId, UUID workspaceId, UUID buyerIdentityId,
                                               UUID salesOrderId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbc.query("select id,tenant_id,workspace_id,buyer_identity_id,sales_order_id,amount,status "
                        + "from payments.buyer_wallet_reservation where tenant_id=? and workspace_id=? "
                        + "and buyer_identity_id=? and sales_order_id=?" + suffix,
                (rs, ignored) -> reservation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getBigDecimal(6), rs.getString(7)), tenantId, workspaceId, buyerIdentityId, salesOrderId)
                .stream().findFirst().orElse(null);
    }

    private ReservationRow reservationByOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbc.query("select id,tenant_id,workspace_id,buyer_identity_id,sales_order_id,amount,status "
                        + "from payments.buyer_wallet_reservation where tenant_id=? and workspace_id=? and sales_order_id=?" + suffix,
                (rs, ignored) -> reservation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getBigDecimal(6), rs.getString(7)), tenantId, workspaceId, salesOrderId)
                .stream().findFirst().orElse(null);
    }

    private ReservationRow reservationById(UUID tenantId, UUID workspaceId, UUID reservationId, boolean lock) {
        String suffix = lock ? " for update" : "";
        return jdbc.query("select id,tenant_id,workspace_id,buyer_identity_id,sales_order_id,amount,status "
                        + "from payments.buyer_wallet_reservation where tenant_id=? and workspace_id=? and id=?" + suffix,
                (rs, ignored) -> reservation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getBigDecimal(6), rs.getString(7)), tenantId, workspaceId, reservationId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Buyer wallet reservation was not found"));
    }

    private ReservationEventRow eventByKey(UUID tenantId, UUID workspaceId, UUID buyerIdentityId, String key) {
        return jdbc.query("select id,reservation_id,event_type,amount from payments.buyer_wallet_reservation_event "
                        + "where tenant_id=? and workspace_id=? and buyer_identity_id=? and idempotency_key=?",
                (rs, ignored) -> new ReservationEventRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getBigDecimal(4)), tenantId, workspaceId, buyerIdentityId, key)
                .stream().findFirst().orElse(null);
    }

    private LedgerRow ledgerByKey(UUID tenantId, UUID workspaceId, UUID buyerIdentityId, String key) {
        return jdbc.query("select id,reservation_id,entry_type,amount_delta,source_id,idempotency_key "
                        + "from payments.buyer_wallet_ledger_entry where tenant_id=? and workspace_id=? "
                        + "and buyer_identity_id=? and idempotency_key=?",
                (rs, ignored) -> ledger(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getObject(5, UUID.class), rs.getString(6)),
                tenantId, workspaceId, buyerIdentityId, key).stream().findFirst().orElse(null);
    }

    private LedgerRow ledgerBySource(UUID tenantId, UUID workspaceId, UUID buyerIdentityId,
                                     String entryType, UUID sourceId) {
        return jdbc.query("select id,reservation_id,entry_type,amount_delta,source_id,idempotency_key "
                        + "from payments.buyer_wallet_ledger_entry where tenant_id=? and workspace_id=? "
                        + "and buyer_identity_id=? and entry_type=? and source_id=?",
                (rs, ignored) -> ledger(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getObject(5, UUID.class), rs.getString(6)),
                tenantId, workspaceId, buyerIdentityId, entryType, sourceId).stream().findFirst().orElse(null);
    }

    private BigDecimal sumLedgerAmount(UUID tenantId, UUID workspaceId, UUID reservationId, String entryType) {
        return jdbc.queryForObject("select coalesce(sum(amount_delta),0) from payments.buyer_wallet_ledger_entry "
                        + "where tenant_id=? and workspace_id=? and reservation_id=? and entry_type=?",
                BigDecimal.class, tenantId, workspaceId, reservationId, entryType);
    }

    private void appendReservationEvent(UUID tenantId, UUID workspaceId, UUID buyerIdentityId,
            UUID reservationId, String eventType, BigDecimal amount, String key, Instant occurredAt) {
        jdbc.update("insert into payments.buyer_wallet_reservation_event "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,reservation_id,event_type,amount,"
                        + "idempotency_key,occurred_at) values (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, buyerIdentityId, reservationId, eventType, amount,
                key, timestamp(occurredAt));
    }

    private Refund replayRefund(LedgerRow row, ReservationRow reservation, RefundCommand command) {
        if (!"REFUND".equals(row.entryType()) || !row.sourceId().equals(command.refundId())
                || !Objects.equals(row.reservationId(), reservation.id())
                || !row.idempotencyKey().equals(command.idempotencyKey())
                || !sameMoney(row.amountDelta(), command.amountPEN())) {
            throw new IllegalStateException("Buyer wallet refund idempotency payload conflict");
        }
        return new Refund(row.id(), command.refundId(), command.amountPEN());
    }

    private static void requireSameReservation(ReservationRow initial, ReservationRow locked) {
        if (!initial.id().equals(locked.id()) || !initial.buyerIdentityId().equals(locked.buyerIdentityId())
                || !initial.salesOrderId().equals(locked.salesOrderId())) {
            throw new IllegalStateException("Buyer wallet reservation changed concurrently");
        }
    }

    private static Reservation result(ReservationRow row) {
        return new Reservation(row.id(), row.salesOrderId(), row.amount(), row.status());
    }

    private static ReservationRow reservation(UUID id, UUID tenantId, UUID workspaceId, UUID buyerIdentityId,
            UUID salesOrderId, BigDecimal amount, String status) {
        return new ReservationRow(id, tenantId, workspaceId, buyerIdentityId, salesOrderId, amount,
                ReservationStatus.valueOf(status));
    }

    private static LedgerRow ledger(UUID id, UUID reservationId, String entryType, BigDecimal amountDelta,
            UUID sourceId, String idempotencyKey) {
        return new LedgerRow(id, reservationId, entryType, amountDelta, sourceId, idempotencyKey);
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record AccountRow(UUID id, BigDecimal posted, BigDecimal reserved) {
        BigDecimal available() { return posted.subtract(reserved); }
    }

    private record ReservationRow(UUID id, UUID tenantId, UUID workspaceId, UUID buyerIdentityId,
            UUID salesOrderId, BigDecimal amount, ReservationStatus status) { }

    private record ReservationEventRow(UUID id, UUID reservationId, String eventType, BigDecimal amount) { }

    private record LedgerRow(UUID id, UUID reservationId, String entryType, BigDecimal amountDelta,
            UUID sourceId, String idempotencyKey) { }

    private record WalletScope(UUID tenantId, UUID workspaceId) { }
}
