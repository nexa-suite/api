package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcTenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseAuthority;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseCredentialsProvider;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSupportOrderReadRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSupportSalesOrderReadQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.support.application.port.out.SupportRequestPersistencePort;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrantValidationPort;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQueryFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;

/** Local-only router activated for explicit Tenant-bound business capabilities. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "'${nexa.tenant-business.buyer-wallet-read.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.buyer-wallet-recharge.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.purchase-request.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.catalog-detail-read.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.catalog-management.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.payments.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.fulfillment-delivery.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.warehouse-operations.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.local-fixtures.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.support-sales-order-read.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.business-documents.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.business-traceability.enabled:false}' == 'true' or "
                + "'${nexa.tenant-business.notifications.enabled:false}' == 'true'")
public class LocalTenantBusinessDatabaseRouterConfiguration {
    @Bean
    TenantBusinessDatabaseBindingRegistry localTenantBusinessDatabaseBindingRegistry(JdbcTemplate centralJdbc) {
        return new JdbcTenantBusinessDatabaseBindingRegistry(centralJdbc);
    }

    @Bean
    TenantBusinessDatabaseAuthority localTenantBusinessDatabaseAuthority(
            ResolveCurrentAccessContextUseCase centralAccessContexts,
            TenantBusinessDatabaseBindingRegistry bindings) {
        return new JdbcTenantBusinessDatabaseAuthority(centralAccessContexts, bindings);
    }

    @Bean
    TenantBusinessDatabaseCredentialsProvider localTenantBusinessDatabaseCredentialsProvider(
            @Value("${nexa.tenant-business.local-credentials-directory:}") String credentialsDirectory) {
        return reference -> {
            if (credentialsDirectory == null || credentialsDirectory.isBlank()) {
                throw new IllegalStateException("Local Tenant database credential directory is not configured");
            }
            return new LocalTenantBusinessDatabaseCredentialsProvider(Path.of(credentialsDirectory))
                    .requireCredentials(reference);
        };
    }

    @Bean(destroyMethod = "close")
    TenantBusinessDatabaseRouter localTenantBusinessDatabaseRouter(
            TenantBusinessDatabaseAuthority authority,
            TenantBusinessDatabaseCredentialsProvider credentials,
            @Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
            @Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        requirements.verifyRequiredSqlAssets();
        return new TenantBusinessDatabaseRouter(authority,
                new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maximumPoolSize), maximumCachedPools,
                requirements.schemaManifestDigest());
    }

    @Bean
    SupportOrderReadGrantValidationPort localSupportOrderReadGrantValidationPort(
            SupportRequestPersistencePort supportRequests) {
        return grant -> supportRequests.isActive(grant, Instant.now());
    }

    @Bean(destroyMethod = "close")
    TenantBusinessDatabaseSupportOrderReadRouter localTenantBusinessDatabaseSupportOrderReadRouter(
            TenantBusinessDatabaseBindingRegistry bindings, SupportOrderReadGrantValidationPort grants,
            TenantBusinessDatabaseCredentialsProvider credentials,
            @Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
            @Value("${nexa.tenant-business.router.maximum-pool-size:4}") int maximumPoolSize) {
        return new TenantBusinessDatabaseSupportOrderReadRouter(bindings, grants,
                new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maximumPoolSize), maximumCachedPools);
    }

    @Bean
    SupportSalesOrderReadQueryFactory localSupportSalesOrderReadQueryFactory(
            TenantBusinessDatabaseSupportOrderReadRouter router) {
        return new TenantBusinessDatabaseSupportSalesOrderReadQueryFactory(router);
    }

    @Bean
    TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory localTenantPolicySnapshotWriterDataSourceFactory(
            @Value("${nexa.tenant-business.local-credentials-directory:}") String credentialsDirectory) {
        var credentials = new LocalTenantBusinessDatabasePolicySnapshotWriterCredentialsProvider(
                Path.of(credentialsDirectory == null ? "" : credentialsDirectory));
        return new DriverManagerTenantBusinessDatabasePolicySnapshotWriterDataSourceFactory(credentials);
    }

    @Bean
    TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver localTenantPurchaseRequestExpiryPolicyResolver(
            OperationalSettingsAccess centralSettings, TenantBusinessDatabaseRouter router,
            TenantBusinessDatabaseAuthority authority,
            TenantBusinessDatabasePolicySnapshotWriterDataSourceFactory writerDataSources) {
        return new TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver(
                centralSettings, router, authority, writerDataSources);
    }
}
