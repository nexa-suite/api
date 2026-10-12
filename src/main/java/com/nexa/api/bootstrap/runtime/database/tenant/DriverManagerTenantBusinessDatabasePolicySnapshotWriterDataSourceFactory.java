package com.nexa.api.bootstrap.runtime.database.tenant;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Objects;
import javax.sql.DataSource;

/** Builds a short-lived writer DataSource from a credential source separate from the runtime pool. */
public final class DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory
		implements TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory {
	private final TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider credentialsProvider;

	public DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory(
			TenantBusinessDatabasePolicySnapshotWriterCredentialsProvider credentialsProvider) {
		this.credentialsProvider = Objects.requireNonNull(credentialsProvider,
				"Dedicated policy-snapshot writer credentials provider is required");
	}

	@Override
	public DataSource create(TenantBusinessDatabaseBinding binding) {
		Objects.requireNonNull(binding, "Tenant business database binding is required");
		var credentials = credentialsProvider.requireWriterCredentials(binding.tenantId(), binding.databaseIdentity());
		var dataSource = new DriverManagerDataSource();
		dataSource.setUrl(credentials.jdbcUrl());
		dataSource.setUsername(credentials.username());
		dataSource.setPassword(credentials.password());
		return dataSource;
	}
}
