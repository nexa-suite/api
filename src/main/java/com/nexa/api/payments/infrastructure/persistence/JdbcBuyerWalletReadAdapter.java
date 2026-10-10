package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-local read adapter; callers must bind it inside the verified router transaction. */
public final class JdbcBuyerWalletReadAdapter implements BuyerWalletReadPort {
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;

    public JdbcBuyerWalletReadAdapter(JdbcTemplate tenantJdbc) {
        this.jdbc = Objects.requireNonNull(tenantJdbc, "Tenant wallet JdbcTemplate is required");
    }

    @Override
    public BuyerWalletModels.Snapshot read(CurrentAccessContext context, UUID humanIdentityId, int page, int size) {
        WalletScope scope = requireCurrentScope(context, humanIdentityId, page, size);
        AccountBalance account = jdbc.query("""
                        SELECT currency, posted_balance, reserved_balance
                        FROM payments.buyer_wallet_account
                        WHERE tenant_id=? AND workspace_id=? AND buyer_identity_id=? AND currency='PEN'
                        """,
                (rs, row) -> new AccountBalance(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                scope.tenantId(), scope.workspaceId(), humanIdentityId).stream().findFirst().orElse(null);
        if (account == null) {
            return new BuyerWalletModels.Snapshot(BuyerWalletModels.WalletStatus.NOT_INITIALIZED,
                    null, null, List.of(), 0);
        }
        if (!"PEN".equals(account.currency()) || account.postedBalance() == null
                || account.reservedBalance() == null || account.postedBalance().signum() < 0
                || account.reservedBalance().signum() < 0
                || account.reservedBalance().compareTo(account.postedBalance()) > 0) {
            throw new IllegalStateException("Buyer wallet balance projection is inconsistent");
        }

        long total = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM payments.buyer_wallet_ledger_entry
                WHERE tenant_id=? AND workspace_id=? AND buyer_identity_id=? AND currency='PEN'
                """, Long.class, scope.tenantId(), scope.workspaceId(), humanIdentityId));
        long offset = (long) page * size;
        List<BuyerWalletModels.MovementView> movements = jdbc.query("""
                        SELECT entry_type, amount_delta, occurred_at
                        FROM payments.buyer_wallet_ledger_entry
                        WHERE tenant_id=? AND workspace_id=? AND buyer_identity_id=? AND currency='PEN'
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, row) -> new BuyerWalletModels.MovementView(
                        movementType(rs.getString(1)), rs.getBigDecimal(2), rs.getObject(3, Timestamp.class).toInstant()),
                scope.tenantId(), scope.workspaceId(), humanIdentityId, size, offset);
        return new BuyerWalletModels.Snapshot(BuyerWalletModels.WalletStatus.ACTIVE,
                account.postedBalance(), account.reservedBalance(), movements, total);
    }

    private WalletScope requireCurrentScope(CurrentAccessContext context, UUID humanIdentityId,
            int page, int size) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        Objects.requireNonNull(humanIdentityId, "Current Buyer identity is required");
        context.requirePermission(PermissionKey.PAYMENT_READ);
        if (!context.userId().value().equals(humanIdentityId)) {
            throw new IllegalStateException("Wallet identity must match the verified current Buyer");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Buyer wallet page bounds are invalid");
        }
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        RlsRequestScope.Scope current = RlsRequestScope.current();
        if (current == null || !tenantId.equals(current.tenantId()) || !workspaceId.equals(current.workspaceId())) {
            throw new IllegalStateException("Buyer wallet read requires the matching verified Tenant and Workspace scope");
        }
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Buyer wallet read requires its active Tenant database transaction");
        }
        return new WalletScope(tenantId, workspaceId);
    }

    private static String movementType(String entryType) {
        return switch (entryType) {
            case "PROVIDER_RECHARGE" -> "CREDIT";
            case "ORDER_CONSUMPTION" -> "ORDER_CONSUMPTION";
            case "REFUND" -> "REFUND";
            default -> throw new IllegalStateException("Buyer wallet contains an unsupported movement type");
        };
    }

    private record WalletScope(UUID tenantId, UUID workspaceId) { }

    private record AccountBalance(String currency, java.math.BigDecimal postedBalance,
                                  java.math.BigDecimal reservedBalance) { }
}
