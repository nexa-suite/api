package com.nexa.api.businesstraceability.tenantdatabase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.function.BiFunction;

/** Routes bounded asynchronous BC-11 work to one centrally verified Tenant/Workspace. */
@FunctionalInterface
public interface TenantBusinessTraceabilityWorkerRouter {
    <T> T inTransaction(UUID tenantId, UUID workspaceId,
                        BiFunction<JdbcTemplate, PlatformTransactionManager, T> work);
}
