package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentials;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseTraceabilityWorkerRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.UUID;

/** Explicitly bound cloud notification worker; it never uses the runtime database login. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-cloud")
@ConditionalOnProperty(name = "nexa.tenant-business.cloud-notification-push.enabled", havingValue = "true")
public class CloudTenantNotificationPushWorkerConfiguration {
    @Bean(destroyMethod = "close")
    TenantBusinessDocumentWorkerScopeQuery notificationPushWorkerScopes(
            @Value("${spring.datasource.url}") String centralUrl,
            @Value("${NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD:}") String password, Environment env) {
        if (password.isBlank() || reusedCentralPassword(env, password)
                || password.equals(env.getProperty("nexa.tenant-business.cloud-router.environment.runtime-password"))
                || password.equals(env.getProperty("nexa.tenant-business.cloud-router.environment.policy-writer-password"))
                || password.equals(env.getProperty("nexa.tenant-business.cloud-router.environment.notification-worker-password"))) {
            throw new IllegalStateException("Dedicated central READY-scope reader credentials are required");
        }
        UUID boundTenantId = UUID.fromString(required(env, "nexa.tenant-business.cloud-router.environment.tenant-id"));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(centralUrl);
        config.setUsername("nexa_business_documents_scope_reader");
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setReadOnly(true);
        config.setPoolName("notification-push-scope-reader");
        return JdbcTenantBusinessDocumentWorkerScopeQuery.forCloudBinding(new HikariDataSource(config), boundTenantId);
    }

    @Bean(destroyMethod = "close")
    TenantBusinessTraceabilityWorkerRouter notificationPushWorkerRouter(TenantBusinessDatabaseBindingRegistry bindings,
            TenantBusinessDocumentWorkerScopeQuery scopes, Environment env,
            @Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maxPools,
            @Value("${nexa.tenant-business.router.maximum-pool-size:4}") int poolSize) {
        String prefix = "nexa.tenant-business.cloud-router.environment.";
        String reference = required(env, prefix + "credential-secret-reference");
        String tenant = required(env, prefix + "tenant-id");
        String identity = required(env, prefix + "database-identity");
        String url = required(env, prefix + "jdbc-url");
        String password = required(env, prefix + "notification-worker-password");
        if (!"environment".equals(env.getProperty("nexa.tenant-business.cloud-router.credentials-source"))
                || !reference.matches("cloud-tenant-db:[a-z0-9][a-z0-9_-]{31,127}")
                || password.equals(env.getProperty(prefix + "runtime-password"))
                || password.equals(env.getProperty(prefix + "policy-writer-password"))
                || reusedCentralPassword(env, password)
                || password.equals(env.getProperty("NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD"))) {
            throw CloudTenantDatabaseSecretFiles.unavailable();
        }
        UUID tenantId = UUID.fromString(tenant);
        UUID databaseIdentity = UUID.fromString(identity);
        CloudTenantDatabaseSecretFiles.requireNeonJdbcUrl(url);
        var factory = new HikariTenantBusinessDatabaseDataSourceFactory(requested -> {
            if (!reference.equals(requested)) throw CloudTenantDatabaseSecretFiles.unavailable();
            return new TenantBusinessDatabaseCredentials(url, "nexa_notification_push_worker", password);
        }, poolSize);
        return new TenantBusinessDatabaseTraceabilityWorkerRouter(bindings, binding -> {
            if (!tenantId.equals(binding.tenantId().value()) || !databaseIdentity.equals(binding.databaseIdentity())
                    || !reference.equals(binding.credentialSecretReference())) {
                throw CloudTenantDatabaseSecretFiles.unavailable();
            }
            return factory.create(binding);
        }, scopes, maxPools, "nexa_notification_push_worker", true);
    }

    private static boolean reusedCentralPassword(Environment env, String password) {
        return password.equals(env.getProperty("spring.datasource.password"))
                || password.equals(env.getProperty("nexa.database.runtime-password"))
                || password.equals(env.getProperty("nexa.database.migrator-password"))
                || password.equals(env.getProperty("NEXA_DATABASE_PASSWORD"))
                || password.equals(env.getProperty("NEXA_DATABASE_RUNTIME_PASSWORD"))
                || password.equals(env.getProperty("NEXA_DATABASE_MIGRATOR_PASSWORD"));
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank() || value.length() > 4096) throw CloudTenantDatabaseSecretFiles.unavailable();
        return value;
    }
}
