package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Payments-owned confirmation read model; no Sales mutation crosses this boundary. */
@Repository
@Profile("!test")
public class JdbcPaymentConfirmationQuery implements PaymentConfirmationQuery {
    private final JdbcTemplate jdbc;

    public JdbcPaymentConfirmationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isConfirmed(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        Boolean confirmed = jdbc.queryForObject("select exists(select 1 from payments.receivable r "
                        + "join payments.payment p on p.tenant_id=r.tenant_id and p.workspace_id=r.workspace_id and p.receivable_id=r.id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.subject_type='SALES_ORDER' and r.subject_id=? "
                        + "and p.status='SUCCEEDED' and r.amount_paid>=r.amount)", Boolean.class,
                tenantId, workspaceId, salesOrderId);
        return Boolean.TRUE.equals(confirmed);
    }
}
