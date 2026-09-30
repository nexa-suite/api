package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Payments-owned confirmation read model; no Sales mutation crosses this boundary. */
@Repository
@Profile("!test")
public class JdbcPaymentConfirmationQuery implements PaymentConfirmationQuery {
    private final JdbcTemplate jdbc;
    private final ReceivablePaymentAccess receivables;

    public JdbcPaymentConfirmationQuery(JdbcTemplate jdbc, ReceivablePaymentAccess receivables) {
        this.jdbc = jdbc;
        this.receivables = receivables;
    }

    @Override
    public boolean isConfirmed(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return receivables.findForSubject(tenantId, workspaceId, salesOrderId, "SALES_ORDER")
                .filter(value -> value.amountPaid().compareTo(value.amount().add(value.adjustmentTotal())) >= 0)
                .map(value -> successfulPayment(tenantId, workspaceId, value.id())).orElse(false);
    }

    @Override
    public boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return receivables.findForSubject(tenantId, workspaceId, salesOrderId, "SALES_ORDER")
                .map(value -> successfulPayment(tenantId, workspaceId, value.id())).orElse(false);
    }

    private boolean successfulPayment(UUID tenantId, UUID workspaceId, UUID receivableId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from payments.payment where tenant_id=? and workspace_id=? and receivable_id=? and status='SUCCEEDED')",
                Boolean.class, tenantId, workspaceId, receivableId));
    }
}
