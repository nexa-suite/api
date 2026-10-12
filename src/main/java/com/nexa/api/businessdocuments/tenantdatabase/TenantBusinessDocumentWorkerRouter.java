package com.nexa.api.businessdocuments.tenantdatabase;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.function.Function;

/**
 * Routes one bounded BC-09 worker callback to the centrally verified Tenant and Workspace.
 * The callback is synchronous and must not retain the JDBC session or return JDBC resources.
 */
@FunctionalInterface
public interface TenantBusinessDocumentWorkerRouter {
    <T> T inTransaction(UUID tenantId, UUID workspaceId, Function<JdbcTemplate, T> work);
}
