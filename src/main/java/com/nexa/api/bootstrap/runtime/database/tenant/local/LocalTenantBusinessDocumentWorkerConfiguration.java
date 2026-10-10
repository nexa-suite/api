package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDocumentOrganizationSnapshotQuery;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDocumentWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentOrganizationSnapshotQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OrganizationDocumentSourceQuery;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/** Local-only wiring for the dedicated, Tenant-bound BC-09 worker route. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-documents.enabled", havingValue = "true")
public class LocalTenantBusinessDocumentWorkerConfiguration {
	@Bean(destroyMethod = "close")
	TenantBusinessDocumentWorkerScopeQuery tenantBusinessDocumentWorkerScopeQuery(
			@Value("${spring.datasource.url}") String centralJdbcUrl,
			@Value("${NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD:}") String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("Dedicated central Business Documents scope-reader credentials are required");
		}
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(centralJdbcUrl);
		config.setUsername("nexa_business_documents_scope_reader");
		config.setPassword(password);
		config.setMaximumPoolSize(2);
		config.setPoolName("business-documents-scope-reader");
		config.setReadOnly(true);
		return new JdbcTenantBusinessDocumentWorkerScopeQuery(new HikariDataSource(config));
	}

	@Bean
	TenantBusinessDocumentOrganizationSnapshotQuery tenantBusinessDocumentOrganizationSnapshotQuery(
			TenantBusinessDocumentWorkerScopeQuery scopes, OrganizationDocumentSourceQuery organizations) {
		return new TenantBusinessDatabaseDocumentOrganizationSnapshotQuery(scopes, organizations);
	}

	@Bean(destroyMethod = "close")
	TenantBusinessDocumentWorkerRouter tenantBusinessDocumentWorkerRouter(
			TenantBusinessDatabaseBindingRegistry bindings,
			TenantBusinessDocumentWorkerScopeQuery scopes,
			@Value("${nexa.tenant-business.local-credentials-directory:}") String credentialsDirectory,
			@Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
			@Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
		if (credentialsDirectory == null || credentialsDirectory.isBlank()) {
			throw new IllegalStateException("Private Tenant credential directory is required for document work");
		}
		TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
		requirements.verifyRequiredSqlAssets();
		TenantBusinessDatabaseCredentialsProvider credentials =
				new LocalTenantBusinessDocumentWorkerCredentialsProvider(Path.of(credentialsDirectory));
		return new com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDocumentWorkerRouter(bindings,
				new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maximumPoolSize), scopes, maximumCachedPools);
	}
}
