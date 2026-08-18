package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.shared.events.PaymentEventContextQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** ACL adapter for payment facts; it does not widen or modify the Payments module. */
@Repository
@Profile("!test")
public class JdbcPaymentEventContextQueryAdapter implements PaymentEventContextQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcPaymentEventContextQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findClientAccountId(UUID tenantId, UUID workspaceId, String aggregateType,
                                               UUID aggregateId) {
        if (aggregateType == null || aggregateId == null) return Optional.empty();
        return switch (aggregateType) {
            case "Receivable" -> one("select client_account_id from payments.receivable "
                    + "where tenant_id=? and workspace_id=? and id=?", tenantId, workspaceId, aggregateId);
            case "Payment" -> one("select client_account_id from payments.payment "
                    + "where tenant_id=? and workspace_id=? and id=?", tenantId, workspaceId, aggregateId);
            default -> Optional.empty();
        };
    }

    private Optional<UUID> one(String sql, UUID tenantId, UUID workspaceId, UUID aggregateId) {
        return jdbc.query(sql, rs -> rs.next() && rs.getObject(1) != null
                ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty(), tenantId, workspaceId, aggregateId);
    }
}
