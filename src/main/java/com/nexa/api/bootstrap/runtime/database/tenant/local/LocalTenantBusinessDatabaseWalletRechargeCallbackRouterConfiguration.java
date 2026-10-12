package com.nexa.api.bootstrap.runtime.database.tenant.local;

import com.nexa.api.bootstrap.runtime.database.tenant.HikariTenantBusinessDatabaseDataSourceFactory;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWalletRechargeCallbackRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/** Local-only callback pool uses the separate least-privilege worker secret. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.buyer-wallet-recharge", name = "enabled", havingValue = "true")
public class LocalTenantBusinessDatabaseWalletRechargeCallbackRouterConfiguration {
    @Bean(destroyMethod = "close")
    TenantBusinessDatabaseWalletRechargeCallbackRouter tenantBusinessDatabaseWalletRechargeCallbackRouter(
            @Value("${nexa.tenant-business.local-credentials-directory:}") String credentialsDirectory,
            @Value("${nexa.tenant-business.router.maximum-cached-pools:8}") int maximumCachedPools,
            @Value("${nexa.tenant-business.router.maximum-pool-size:2}") int maximumPoolSize) {
        var credentials = new LocalTenantWalletRechargeWorkerCredentialsProvider(
                Path.of(credentialsDirectory == null ? "" : credentialsDirectory));
        return new TenantBusinessDatabaseWalletRechargeCallbackRouter(
                new HikariTenantBusinessDatabaseDataSourceFactory(credentials, maximumPoolSize), maximumCachedPools);
    }
}
