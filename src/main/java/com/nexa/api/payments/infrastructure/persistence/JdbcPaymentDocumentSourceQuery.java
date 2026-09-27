package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Payments-owned immutable source facts for payment documents. */
@Repository
@Profile("!test")
public class JdbcPaymentDocumentSourceQuery implements PaymentDocumentSourceQuery {
    private final JdbcTemplate jdbc;

    public JdbcPaymentDocumentSourceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Snapshot> find(UUID tenantId, UUID workspaceId, UUID paymentId) {
        return jdbc.query("select id,client_account_id,receivable_id,amount,currency,method,status,"
                        + "provider_payment_intent_id,created_at from payments.payment "
                        + "where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new Snapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getObject("receivable_id", UUID.class),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("method"),
                        rs.getString("status"), rs.getString("provider_payment_intent_id"),
                        rs.getTimestamp("created_at").toInstant()), tenantId, workspaceId, paymentId)
                .stream().findFirst();
    }
}
