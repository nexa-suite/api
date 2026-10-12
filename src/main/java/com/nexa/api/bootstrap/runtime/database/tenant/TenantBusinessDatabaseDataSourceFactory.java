package com.nexa.api.bootstrap.runtime.database.tenant;

import javax.sql.DataSource;

@FunctionalInterface
public interface TenantBusinessDatabaseDataSourceFactory {
	DataSource create(TenantBusinessDatabaseBinding binding);
}
