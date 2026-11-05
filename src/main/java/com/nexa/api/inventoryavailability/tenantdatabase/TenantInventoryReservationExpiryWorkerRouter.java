package com.nexa.api.inventoryavailability.tenantdatabase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.function.BiFunction;

/** Runs one synchronous reservation-expiry callback in its exact Tenant database scope. */
public interface TenantInventoryReservationExpiryWorkerRouter {
	<T> T inTransaction(UUID tenantId, UUID workspaceId,
			BiFunction<JdbcTemplate, PlatformTransactionManager, T> work);
}
