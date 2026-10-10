package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.JdbcPaymentProviderRouteRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePaymentCallbackRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/** Local-only callback router uses an isolated Tenant Payments worker credential. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.payments", name = "enabled", havingValue = "true")
public class LocalTenantBusinessDatabasePaymentCallbackRouterConfiguration {
    @Bean(destroyMethod = "close")
    TenantBusinessDatabasePaymentCallbackRouter tenantBusinessDatabasePaymentCallbackRouter(
            JdbcPaymentProviderRouteRegistry routes,
            @Value("${nexa.tenant-business.local-credentials-directory:}") String credentialsDirectory,
            @Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
            @Value("${nexa.tenant-business.router.maximum-pool-size:2}") int maximumPoolSize) {
        if (credentialsDirectory == null || credentialsDirectory.isBlank()) {
            throw new IllegalStateException("Local Tenant database credential directory is not configured");
        }
        var credentials = new LocalTenantPaymentCallbackCredentialsProvider(Path.of(credentialsDirectory));
        return new TenantBusinessDatabasePaymentCallbackRouter(routes,
                new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maximumPoolSize), maximumCachedPools);
    }
}
