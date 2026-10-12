package com.nexa.api.support;

import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.bootstrap.local.LocalTenantBusinessFixtureSeeder;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcPaymentProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePaymentCallbackRouter;
import com.nexa.api.bootstrap.local.seed.ClientAccountSeedLoader;
import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseDocumentWorkerRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseTraceabilityWorkerRouter;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSkuPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogTenantLocalFixtureProjection;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogLocalFixtureProjection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Maps only explicit integration-fixture references to the host-mapped Tenant PostgreSQL server. */
@TestConfiguration(proxyBeanMethods = false)
class TenantBusinessIntegrationTestConfiguration {
	@Bean(destroyMethod = "close")
	@Primary
	@ConditionalOnProperty(name = "nexa.tenant-business.payments.enabled", havingValue = "true")
	TenantBusinessDatabasePaymentCallbackRouter integrationTenantPaymentCallbackRouter(
			JdbcPaymentProviderRouteRegistry routes,
			@Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
			@Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
		return new TenantBusinessDatabasePaymentCallbackRouter(routes,
				new HikariTenantBusinessDatabaseDataSourceFactory(
						TenantBusinessIntegrationFixture::paymentCallbackWorkerCredentials, maximumPoolSize),
				maximumCachedPools);
	}

	@Bean
	TenantCatalogLocalFixtureProjection integrationTenantCatalogLocalFixtureProjection(
			CatalogPersistenceBootstrap catalogSeed, CatalogSkuPersistenceBootstrap skuSeed,
			CatalogFamilySkuMappingLoader catalogMappings) {
		return new CatalogTenantLocalFixtureProjection(catalogSeed, skuSeed, catalogMappings);
	}

	@Bean
	LocalTenantBusinessFixtureSeeder integrationTenantBusinessFixtureSeeder(
			ClientAccountSeedLoader accountSeeds, TenantCatalogLocalFixtureProjection catalogFixtures) {
		return new LocalTenantBusinessFixtureSeeder(accountSeeds, catalogFixtures);
	}

	@Bean(destroyMethod = "close")
	@Primary
	@ConditionalOnProperty(name = "nexa.tenant-business.business-documents.enabled", havingValue = "true")
	TenantBusinessDocumentWorkerRouter integrationTenantBusinessDocumentWorkerRouter(
			TenantBusinessDatabaseBindingRegistry bindings, TenantBusinessDocumentWorkerScopeQuery scopes,
			@Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
			@Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
		return new TenantBusinessDatabaseDocumentWorkerRouter(bindings,
				new HikariTenantBusinessDatabaseDataSourceFactory(
						TenantBusinessIntegrationFixture::documentWorkerCredentials, maximumPoolSize),
				scopes, maximumCachedPools);
	}

	@Bean(destroyMethod = "close")
	@Primary
	@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
	TenantBusinessTraceabilityWorkerRouter integrationTenantBusinessTraceabilityWorkerRouter(
			TenantBusinessDatabaseBindingRegistry bindings, TenantBusinessDocumentWorkerScopeQuery scopes,
			@Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
			@Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
		return new TenantBusinessDatabaseTraceabilityWorkerRouter(bindings,
				new HikariTenantBusinessDatabaseDataSourceFactory(
						TenantBusinessIntegrationFixture::traceabilityWorkerCredentials, maximumPoolSize),
				scopes, maximumCachedPools);
	}

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
