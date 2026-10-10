package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantBusinessDatabaseProvisioningStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.TenantBusinessDatabaseProvisioningStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/** Central read adapter; the caller must place it behind explicit internal-operator authentication. */
@Component
public final class JdbcTenantBusinessDatabaseProvisioningStatusQuery
        implements TenantBusinessDatabaseProvisioningStatusQuery {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcTenantBusinessDatabaseProvisioningStatusQuery(JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setReadOnly(true);
    }

    @Override
    public List<TenantBusinessDatabaseProvisioningStatus> listRecent(UUID internalOperatorId, int limit) {
        if (internalOperatorId == null) throw new AccessDeniedException("An authenticated internal operator is required");
        int boundedLimit = Math.max(1, Math.min(100, limit));
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.queryForObject("select set_config('app.current_internal_operator_id', ?, true)",
                    String.class, internalOperatorId.toString());
            return jdbc.query("select task.tenant_id,task.workspace_id,task.status,task.attempt_count,task.updated_at,"
                            + "coalesce(binding.lifecycle_state,'UNBOUND') as registry_lifecycle_state "
                            + "from tenant_management.tenant_business_database_provisioning_task task "
                            + "left join tenant_management.tenant_business_database_binding binding "
                            + "on binding.tenant_id=task.tenant_id "
                            + "order by task.updated_at desc,task.tenant_id,task.workspace_id limit ?",
                    (rs, row) -> new TenantBusinessDatabaseProvisioningStatus(
                            rs.getObject("tenant_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                            rs.getString("status"), rs.getInt("attempt_count"), rs.getTimestamp("updated_at").toInstant(),
                            rs.getString("registry_lifecycle_state")),
                    boundedLimit);
        }));
    }
}
