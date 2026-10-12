package com.nexa.api.bootstrap.runtime.database.tenant;

import org.springframework.jdbc.core.JdbcTemplate;

@FunctionalInterface
public interface TenantBusinessDatabaseWork<T> {
	/**
	 * Runs inline on the calling thread inside one local Tenant transaction. Work must finish
	 * before returning, must not schedule or spawn asynchronous work, and must not retain the
	 * supplied JdbcTemplate or JDBC resources after returning. The router's central-data-source
	 * fence is thread-local and does not apply to other threads.
	 */
	T execute(JdbcTemplate tenantJdbc);
}
