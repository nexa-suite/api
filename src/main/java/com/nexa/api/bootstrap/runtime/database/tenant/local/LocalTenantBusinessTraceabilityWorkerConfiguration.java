package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseTraceabilityWorkerRouter;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/** Local-only route for BC-11's dedicated Tenant outbox consumer. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public class LocalTenantBusinessTraceabilityWorkerConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${nexa.tenant-business.business-documents.enabled:false}' != 'true'")
    TenantBusinessDocumentWorkerScopeQuery tenantTraceabilityWorkerScopeQuery(
            @Value("${spring.datasource.url}") String centralJdbcUrl,
            @Value("${NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD:}") String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Dedicated central READY-scope reader credentials are required");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(centralJdbcUrl);
        config.setUsername("nexa_business_documents_scope_reader");
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setPoolName("business-traceability-scope-reader");
        config.setReadOnly(true);
        return new JdbcTenantBusinessDocumentWorkerScopeQuery(new HikariDataSource(config));
    }

    @Bean(destroyMethod = "close")
    TenantBusinessTraceabilityWorkerRouter tenantBusinessTraceabilityWorkerRouter(
            TenantBusinessDatabaseBindingRegistry bindings, TenantBusinessDocumentWorkerScopeQuery scopes,
            @Value("${nexa.tenant-business.local-credentials-directory:}") String credentialsDirectory,
            @Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
            @Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
        if (credentialsDirectory == null || credentialsDirectory.isBlank()) {
            throw new IllegalStateException("Private Tenant credential directory is required for traceability work");
        }
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        requirements.verifyRequiredSqlAssets();
        TenantBusinessDatabaseCredentialsProvider credentials =
                new LocalTenantBusinessTraceabilityWorkerCredentialsProvider(Path.of(credentialsDirectory));
        return new TenantBusinessDatabaseTraceabilityWorkerRouter(bindings,
                new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maximumPoolSize),
                scopes, maximumCachedPools);
    }
}
