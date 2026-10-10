package com.nexa.api.support;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Maps only explicit integration-fixture references to the host-mapped Tenant PostgreSQL server. */
@TestConfiguration(proxyBeanMethods = false)
class TenantBusinessIntegrationTestConfiguration {
	@Bean
	@Primary
	TenantBusinessDatabaseCredentialsProvider integrationTenantBusinessDatabaseCredentialsProvider() {
		return TenantBusinessIntegrationFixture::credentials;
	}

	@Bean
	@Primary
	TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory integrationTenantPolicySnapshotWriterDataSourceFactory() {
		return TenantBusinessIntegrationFixture::policyWriterDataSource;
	}
}
