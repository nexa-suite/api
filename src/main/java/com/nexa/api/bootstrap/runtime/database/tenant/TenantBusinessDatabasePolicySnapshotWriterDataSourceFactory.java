package com.nexa.api.bootstrap.runtime.database.tenant;

import javax.sql.DataSource;

/** Creates a separate, least-privilege connection source for local policy-snapshot writes. */
@FunctionalInterface
public interface TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory {
	DataSource create(TenantBusinessDatabaseBinding binding);
}
