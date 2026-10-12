package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.BuyerWalletRefundBalanceQuery;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reads only BC-08 wallet reservations and ledger on the bound Tenant JDBC session. */
public final class JdbcBuyerWalletRefundBalanceQuery implements BuyerWalletRefundBalanceQuery {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private final JdbcTemplate jdbc;

    public JdbcBuyerWalletRefundBalanceQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "Tenant JDBC session is required");
    }

    @Override
    public Map<UUID, RefundableBalance> findRefundableBalances(CurrentAccessContext context,
            Collection<UUID> salesOrderIds) {
        Objects.requireNonNull(context, "Verified access context is required")
                .requirePermission(PermissionKey.PAYMENT_RECONCILE);
        Objects.requireNonNull(salesOrderIds, "Sales Order ids are required");
        List<UUID> orderIds = salesOrderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (orderIds.isEmpty()) return Map.of();
        DataSource dataSource = jdbc.getDataSource();
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource) || scope == null
                || !tenantId.equals(scope.tenantId()) || !workspaceId.equals(scope.workspaceId())) {
            throw new IllegalStateException("Buyer wallet refund query requires the bound Tenant transaction");
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        String sql = "select r.sales_order_id, "
                + "coalesce(-sum(l.amount_delta) filter (where l.entry_type='ORDER_CONSUMPTION'),0) consumed, "
                + "coalesce(sum(l.amount_delta) filter (where l.entry_type='REFUND'),0) refunded "
                + "from payments.buyer_wallet_reservation r left join payments.buyer_wallet_ledger_entry l "
                + "on l.tenant_id=r.tenant_id and l.workspace_id=r.workspace_id and l.reservation_id=r.id "
                + "where r.tenant_id=? and r.workspace_id=? and r.sales_order_id in (" + placeholders + ") "
                + "and r.status='CONSUMED' group by r.sales_order_id";
        Object[] args = new Object[2 + orderIds.size()];
        args[0] = tenantId;
        args[1] = workspaceId;
        for (int index = 0; index < orderIds.size(); index++) args[index + 2] = orderIds.get(index);
        Map<UUID, RefundableBalance> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            UUID salesOrderId = rs.getObject("sales_order_id", UUID.class);
            BigDecimal consumed = rs.getBigDecimal("consumed");
            BigDecimal refunded = rs.getBigDecimal("refunded");
            if (consumed == null) consumed = ZERO;
            if (refunded == null) refunded = ZERO;
            BigDecimal refundable = consumed.subtract(refunded).max(ZERO);
            result.put(salesOrderId, new RefundableBalance(consumed, refunded, refundable));
        }, args);
        return Map.copyOf(result);
    }
}
