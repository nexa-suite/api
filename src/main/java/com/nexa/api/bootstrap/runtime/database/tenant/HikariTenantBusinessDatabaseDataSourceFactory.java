package com.nexa.api.bootstrap.runtime.database.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Objects;

/** Builds a Tenant-only pool from business credentials resolved outside the central registry. */
public final class HikariTenantBusinessDatabaseDataSourceFactory implements TenantBusinessDatabaseDataSourceFactory {
	private final TenantBusinessDatabaseCredentialsProvider credentialsProvider;
	private final int maximumPoolSize;

	public HikariTenantBusinessDatabaseDataSourceFactory(
			TenantBusinessDatabaseCredentialsProvider credentialsProvider, int maximumPoolSize) {
		this.credentialsProvider = Objects.requireNonNull(credentialsProvider,
				"Tenant business database credentials provider is required");
		if (maximumPoolSize < 1) throw new IllegalArgumentException("Pool size must be positive");
		this.maximumPoolSize = maximumPoolSize;
	}

	@Override
	public HikariDataSource create(TenantBusinessDatabaseBinding binding) {
		Objects.requireNonNull(binding, "Tenant database binding is required");
		TenantBusinessDatabaseCredentials credentials = credentialsProvider
				.requireCredentials(binding.credentialSecretReference());
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(credentials.jdbcUrl());
		config.setUsername(credentials.username());
		config.setPassword(credentials.password());
		config.setMaximumPoolSize(maximumPoolSize);
		config.setPoolName("tenant-business-" + binding.databaseIdentity());
		return new HikariDataSource(config);
	}
}
